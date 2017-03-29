/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.server.rules;

import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.builder.model.KieSessionModel;
import org.kie.api.conf.EqualityBehaviorOption;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.conf.TimedRuleExectionOption;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.SessionClock;
import org.kie.internal.command.CommandFactory;
import org.openremote.container.util.Util;
import org.openremote.manager.server.asset.AssetProcessingService;
import org.openremote.manager.server.asset.AssetStorageService;
import org.openremote.manager.server.asset.ServerAsset;
import org.openremote.manager.shared.rules.AssetRuleset;
import org.openremote.manager.shared.rules.GlobalRuleset;
import org.openremote.manager.shared.rules.TenantRuleset;
import org.openremote.model.AttributeEvent;
import org.openremote.model.Consumer;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetQuery;
import org.openremote.model.asset.AssetUpdate;
import org.openremote.manager.shared.rules.Ruleset;
import org.openremote.model.rules.Assets;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RulesDeployment<T extends Ruleset> {

    public static final Logger LOG = Logger.getLogger(RulesDeployment.class.getName());

    final protected AssetStorageService assetStorageService;
    final protected AssetProcessingService assetProcessingService;
    final protected  Class<T> rulesetType;
    final protected String id;

    // This is here so Clock Type can be set to pseudo from tests
    protected static ClockTypeOption DefaultClockType;
    private static final int AUTO_START_DELAY_SECONDS = 2;
    private static Long counter = 1L;
    static final protected Util UTIL = new Util();
    protected final Map<Long, T> rulesets = new LinkedHashMap<>();
    protected final RuleExecutionLogger ruleExecutionLogger = new RuleExecutionLogger();
    protected KieSession knowledgeSession;
    protected KieServices kieServices;
    protected KieFileSystem kfs;
    // We need to be able to reference the KieModule dynamically generated for this engine
    // from the singleton KieRepository to do this we need a pom.xml file with a release ID - crazy drools!!
    protected ReleaseId releaseId;
    protected Throwable error;
    protected boolean running;
    protected long currentFactCount;
    final protected Map<AssetUpdate, FactHandle> facts = new HashMap<>();
    protected static final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    protected ScheduledFuture startTimer;

    public RulesDeployment(AssetStorageService assetStorageService, AssetProcessingService assetProcessingService,
                           Class<T> rulesetType, String id) {
        this.assetStorageService = assetStorageService;
        this.assetProcessingService = assetProcessingService;
        this.rulesetType = rulesetType;
        this.id = id;
    }

    protected synchronized static Long getNextCounter() {
        return counter++;
    }

    @SuppressWarnings("unchecked")
    public synchronized T[] getAllRulesets() {
        T[]arr = Util.createArray(rulesets.size(), rulesetType);
        return rulesets.values().toArray(arr);
    }

    public String getId() {
        return id;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isError() {
        return error != null;
    }

    public Throwable getError() {
        return error;
    }

    public KieSession getKnowledgeSession() {
        return knowledgeSession;
    }

    public SessionClock getSessionClock() {
        KieSession session = getKnowledgeSession();
        if (session != null) {
            return session.getSessionClock();
        }

        return null;
    }

    public synchronized boolean isEmpty() {
        return rulesets.isEmpty();
    }

    protected void setGlobal(String identifier, Object object) {
        try {
            knowledgeSession.setGlobal(identifier, object);
        } catch (Throwable t) {
            // Ignore, Drools complains if the DRL doesn't declare the global, but it works
        }
    }

    /**
     * Adds the ruleset to the engine by first stopping the engine and
     * then deploying new rules and then restarting the engine (after
     * {@link #AUTO_START_DELAY_SECONDS}) to prevent excessive engine stop/start.
     * <p>
     * If engine is in an error state (one of the rulesets failed to deploy
     * then the engine will not restart).
     *
     * @return Whether or not the ruleset deployed successfully
     */
    public synchronized boolean insertRuleset(T ruleset) {
        if (ruleset == null || ruleset.getRules() == null || ruleset.getRules().isEmpty()) {
            // Assume it's a success if deploying an empty ruleset
            LOG.finest("Ruleset is empty so no rules to deploy");
            return true;
        }

        if (kfs == null) {
            initialiseEngine();
        }

        T existingRuleset = rulesets.get(ruleset.getId());

        if (existingRuleset != null && existingRuleset.getVersion() == ruleset.getVersion()) {
            LOG.fine("Ruleset version already deployed so ignoring");
            return true;
        }

        // TODO: What is the best way to handle live deployment of new rules
        if (isRunning()) {
            stop();
        }

        // Stop any running start timer
        if (startTimer != null) {
            startTimer.cancel(false);
        }

        // Check if ruleset is already deployed (maybe an older version)
        if (existingRuleset != null) {
            // Remove this old rules file
            kfs.delete("src/main/resources/" + ruleset.getId());
            //noinspection SuspiciousMethodCalls
            rulesets.remove(existingRuleset);
        }

        LOG.info("Adding ruleset: " + ruleset);

        boolean addSuccessful = false;

        try {
            // ID will be unique within the scope of a rules deployment as ruleset will all be of same type
            kfs.write("src/main/resources/" + ruleset.getId() + ".drl", ruleset.getRules());
            // Unload the rules string from the ruleset we don't need it anymore and don't want it using memory
            ruleset.setRules(null);
            KieBuilder kieBuilder = kieServices.newKieBuilder(kfs).buildAll();

            if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
                Collection<Message> errors = kieBuilder.getResults().getMessages(Message.Level.ERROR);
                LOG.severe("Error in ruleset: " + ruleset);
                for (Message error : errors) {
                    LOG.severe(error.getText());
                }
                // If compilation failed, remove rules from FileSystem so it won't fail on next pass here if any
                kfs.delete("src/main/resources/" + ruleset.getId());
            } else {
                LOG.info("Added ruleset: " + ruleset);
                addSuccessful = true;
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error in ruleset: " + ruleset, e);
            error = e;
            // If compilation failed, remove rules from FileSystem so it won't fail on next pass here if any
            kfs.delete("src/main/resources/" + ruleset.getId());
        }

        if (!addSuccessful) {
            error = new RuntimeException("Ruleset contains an error: " + ruleset);

            // Update status of each ruleset
            rulesets.forEach((id, rd) -> {
                if (rd.getDeploymentStatus() == Ruleset.DeploymentStatus.DEPLOYED) {
                    rd.setDeploymentStatus(Ruleset.DeploymentStatus.READY);
                }
            });
        } else {
            startTimer = executorService.schedule(this::start, AUTO_START_DELAY_SECONDS, TimeUnit.SECONDS);
        }

        // Add new ruleset
        ruleset.setDeploymentStatus(addSuccessful ? Ruleset.DeploymentStatus.DEPLOYED : Ruleset.DeploymentStatus.FAILED);
        rulesets.put(ruleset.getId(), ruleset);

        return addSuccessful;
    }

    protected synchronized void retractRuleset(Ruleset ruleset) {
        if (kfs == null) {
            return;
        }

        T matchedRuleset = rulesets.get(ruleset.getId());
        if (matchedRuleset == null) {
            LOG.finer("Ruleset cannot be retracted as it was never deployed: " + ruleset);
            return;
        }

        if (isRunning()) {
            stop();
        }

        // Stop any running start timer
        if (startTimer != null) {
            startTimer.cancel(false);
        }

        // Remove this old rules file
        kfs.delete("src/main/resources/" + ruleset.getId());
        rulesets.remove(ruleset.getId());

        // Update status of each ruleset
        boolean anyFailed = rulesets
            .values()
            .stream()
            .anyMatch(rd -> rd.getDeploymentStatus() == Ruleset.DeploymentStatus.FAILED);

        if (!anyFailed) {
            error = null;
            rulesets.forEach((id, rd) -> {
                if (rd.getDeploymentStatus() == Ruleset.DeploymentStatus.READY) {
                    rd.setDeploymentStatus(Ruleset.DeploymentStatus.DEPLOYED);
                }
            });
        }

        if (!isError() && !isEmpty()) {
            // Queue engine start
            startTimer = executorService.schedule(this::start, AUTO_START_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    protected void initialiseEngine() {
        // Initialise
        kieServices = KieServices.Factory.get();
        KieModuleModel kieModuleModel = kieServices.newKieModuleModel();

        String versionId = getNextCounter().toString();
        releaseId = kieServices.newReleaseId("org.openremote", "openremote-kiemodule", versionId);
        KieBaseModel kieBaseModel = kieModuleModel.newKieBaseModel("OpenRemoteKModule");
        ClockTypeOption clockType = DefaultClockType != null ? DefaultClockType : ClockTypeOption.get("realtime");

        kieBaseModel
                .setDefault(true)
                .setEqualsBehavior(EqualityBehaviorOption.EQUALITY)
                .setEventProcessingMode(EventProcessingOption.STREAM)
                .newKieSessionModel("ksession1")
                .setDefault(true)
                .setType(KieSessionModel.KieSessionType.STATEFUL)
                .setClockType(clockType);
        kfs = kieServices.newKieFileSystem();
        kfs.generateAndWritePomXML(releaseId);
        kfs.writeKModuleXML(kieModuleModel.toXML());

        LOG.fine("Initialised rules service for deployment '" + getId() + "'");
        LOG.info(kieBaseModel.toString());
    }

    protected synchronized void start() {
        if (isRunning()) {
            return;
        }

        if (isError()) {
            LOG.fine("Cannot start rules engine as an error occurred during startup");
            return;
        }

        if (isEmpty()) {
            LOG.finest("No rulesets loaded so nothing to start");
            return;
        }

        LOG.fine("Starting RuleEngine: " + this);

        // Note each rule engine has its' own KieModule which are stored in a singleton register by drools
        // we need to ensure we get the right module here otherwise we could be using the wrong rules
        KieContainer kieContainer = kieServices.newKieContainer(releaseId);
        KieSessionConfiguration kieSessionConfiguration = kieServices.newKieSessionConfiguration();
        // Use this option to ensure timer rules are fired even in passive mode (triggered by fireAllRules.
        // This ensures compatibility with the behaviour of previously used Drools 5.1
        kieSessionConfiguration.setOption(TimedRuleExectionOption.YES);
        try {
            knowledgeSession = kieContainer.newKieSession(kieSessionConfiguration);
            running = true;

            setGlobal("assets", createAssetsFacade());
            setGlobal("LOG", LOG);

            // TODO Still need this UTIL?
            setGlobal("util", UTIL);

            knowledgeSession.addEventListener(ruleExecutionLogger);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to create the rules engine session", e);
            error = e;
            stop();
            throw e;
        }

        // Insert all the facts into the session
        List<AssetUpdate> factEntries = new ArrayList<>(facts.keySet());
        factEntries.forEach(factEntry -> insertFactIntoSession(factEntry, true));

        LOG.info("Rule engine started");
        knowledgeSession.fireAllRules();
    }

    protected synchronized void stop() {
        if (!isRunning()) {
            return;
        }

        LOG.fine("Stopping RuleEngine: " + this);

        if (knowledgeSession != null) {
            knowledgeSession.halt();
            knowledgeSession.dispose();
            LOG.fine("Knowledge session disposed");
        }

        running = false;
    }

    protected synchronized void insertFact(AssetUpdate assetUpdate, boolean silent) {
        // Check if fact already exists using equals()
        if (!facts.containsKey(assetUpdate)) {
            // Delete any existing fact for this attribute ref
            // Put the fact into working memory and store the handle
            retractFact(assetUpdate);

            if (isRunning()) {
                insertFactIntoSession(assetUpdate, silent);
            } else {
                facts.put(assetUpdate, null);
            }
        }
    }

    protected synchronized void retractFact(AssetUpdate assetUpdate) {

        // If there already is a fact in working memory for this attribute then delete it
        AssetUpdate update = facts.keySet()
                .stream()
                .filter(au -> au.attributeRefsEqual(assetUpdate))
                .findFirst()
                .orElse(null);

        FactHandle factHandle = update != null ? facts.get(update) : null;

        if (factHandle != null) {
            facts.remove(update);

            if (isRunning()) {
                try {
                    // ... retract it from working memory ...
                    LOG.finest("Removed stale fact '" + update + "' in: " + this);
                    knowledgeSession.delete(factHandle);
                    int fireCount = knowledgeSession.fireAllRules();
                } catch (Exception e) {
                    LOG.warning("Failed to retract fact '" + update + "' in: " + this);
                }
            }
        } else {
            LOG.fine("No fact handle for '" + assetUpdate + "' in: " + this);
        }
    }

    protected FactHandle insertFactIntoSession(AssetUpdate assetUpdate, boolean silent) {
        FactHandle factHandle = null;

        if (isRunning()) {
            try {
                long newFactCount;
                factHandle = knowledgeSession.insert(assetUpdate);
                facts.put(assetUpdate, factHandle);
                LOG.finest("Inserting fact '" + assetUpdate + "' in: " + this);
                LOG.finest("On " + this + ", firing all rules");
//                int fireCount = knowledgeSession.fireAllRules();
                FactHandle finalFactHandle = factHandle;
                int fireCount = knowledgeSession.fireAllRules((match) -> !silent || !match.getFactHandles().contains(finalFactHandle));
                LOG.finest("On " + this + ", fired rules count: " + fireCount);

                // TODO: Prevent run away fact creation (not sure how we can do this reliably as facts can be generated in rule RHS)
                // MR: this is heuristic number which comes good for finding facts memory leak in the drl file.
                // problem - when you are not careful then drl can insert new facts till memory exhaustion. As there
                // are usually few 100 facts in drl's I'm working with, putting arbitrary number gives me early feedback
                // that there is potential problem. Perhaps we should think about a better solution to this problem?
                newFactCount = knowledgeSession.getFactCount();
                LOG.finest("On " + this + ", new fact count: " + newFactCount);
                if (newFactCount != currentFactCount) {
                    LOG.finest("On " + this + ", fact count changed from " + currentFactCount + " to " + newFactCount + " after: " + assetUpdate);
                }

                currentFactCount = newFactCount;

            } catch (Exception e) {
                // We can end up here because of errors in rule RHS - bubble up the cause
                error = e;
                stop();
                throw new RuntimeException(e);
            }
        } else {
            LOG.fine("Engine is in error state or not running (" + toString() + "), ignoring: " + assetUpdate);
        }

        return factHandle;
    }

    protected Assets createAssetsFacade() {
        return new Assets() {
            @Override
            public RestrictedQuery query() {
                RestrictedQuery query = new RestrictedQuery() {

                    @Override
                    public RestrictedQuery select(Select select) {
                        throw new IllegalArgumentException("Overriding query projection is not allowed in this rules scope");
                    }

                    @Override
                    public RestrictedQuery id(String id) {
                        throw new IllegalArgumentException("Overriding query restriction is not allowed in this rules scope");
                    }

                    @Override
                    public RestrictedQuery tenant(TenantPredicate tenantPredicate) {
                        if (GlobalRuleset.class.isAssignableFrom(rulesetType))
                            return super.tenant(tenantPredicate);
                        throw new IllegalArgumentException("Overriding query restriction is not allowed in this rules scope");
                    }

                    @Override
                    public RestrictedQuery userId(String userId) {
                        throw new IllegalArgumentException("Overriding query restriction is not allowed in this rules scope");
                    }

                    @Override
                    public RestrictedQuery orderBy(OrderBy orderBy) {
                        throw new IllegalArgumentException("Overriding query result order is not allowed in this rules scope");
                    }

                    @Override
                    public String getResult() {
                        ServerAsset asset = assetStorageService.find(this);
                        return asset != null ? asset.getId() : null;
                    }

                    @Override
                    public List<String> getResults() {
                        return assetStorageService.findAllIds(this);
                    }

                    @Override
                    public void applyResult(Consumer<String> assetIdConsumer) {
                        assetIdConsumer.accept(getResult());
                    }

                    @Override
                    public void applyResults(Consumer<List<String>> assetIdListConsumer) {
                        assetIdListConsumer.accept(getResults());
                    }
                };

                if (TenantRuleset.class.isAssignableFrom(rulesetType)) {
                    query.tenantPredicate = new AssetQuery.TenantPredicate(id);
                }
                if (AssetRuleset.class.isAssignableFrom(rulesetType)) {
                    ServerAsset restrictedAsset = assetStorageService.find(id, true);
                    if (restrictedAsset == null) {
                        throw new IllegalStateException("Asset is no longer available for this deployment: " + id);
                    }
                    query.pathPredicate = new AssetQuery.PathPredicate(restrictedAsset.getPath());
                }
                return query;
            }

            @Override
            public void dispatch(AttributeEvent event) {
                // Check if the asset ID of the event can be found in the original query
                AssetQuery checkQuery = query();
                checkQuery.id = event.getEntityId();
                if (assetStorageService.find(checkQuery) == null) {
                    throw new IllegalArgumentException(
                        "Access to asset not allowed for this rule engine scope: " + event
                    );
                }
                LOG.fine("Dispatching on " + RulesDeployment.this + ": " + event);
                assetProcessingService.updateAttributeValue(event);
            }
        };
    }

    protected synchronized String getRulesetDebug() {
        return Arrays.toString(rulesets.values().stream().map(rd ->
            rd.getClass().getSimpleName()
                + " - "
                + rd.getName()
                + ": "
                + rd.getDeploymentStatus()).toArray(String[]::new)
        );
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
            "id='" + id + '\'' +
            ", running='" + running + '\'' +
            ", error='" + error + '\'' +
            ", rulesets='" + getRulesetDebug() + '\'' +
            '}';
    }
}