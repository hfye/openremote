package org.openremote.agent.protocol.upnp;

import com.fasterxml.uuid.Generators;
import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.registry.RegistryListener;
import org.openremote.agent.protocol.AbstractProtocol;
import org.openremote.model.AbstractValueHolder;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.asset.AssetType;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.Values;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.openremote.model.Constants.PROTOCOL_NAMESPACE;
import static org.openremote.model.asset.AssetMeta.LABEL;
import static org.openremote.model.attribute.AttributeType.STRING;

// TODO Experimental

public class UpnpProtocol extends AbstractProtocol {

    private static final Logger LOG = Logger.getLogger(UpnpProtocol.class.getName());

    public static final String PROTOCOL_NAME = PROTOCOL_NAMESPACE + ":upnp";
    public static final String PROTOCOL_DISPLAY_NAME = "UPnP";

    /**
     * The asset under which all discovered devices are merged.
     */
    public static final String GROUP_ASSET_ID = PROTOCOL_NAME + ":groupAssetId";

    protected static final String VERSION = "1.0";

    protected Map<AttributeRef, String> protocolConfigsGroupAssetId = new HashMap<>();

    static protected UpnpService INSTANCE = null;

    final protected RegistryListener registryListener = new DefaultRegistryListener() {
        @Override
        public void deviceAdded(Registry registry, Device device) {
            LOG.fine("UPnP device discovered: " + device.getDisplayString());
            linkedProtocolConfigurations.values().forEach(linkedProtocolInfo -> {
                storeAssets(linkedProtocolInfo.getProtocolConfiguration(), device);
            });
        }

        @Override
        public void deviceRemoved(Registry registry, Device device) {
            LOG.fine("UPnP device gone: " + device.getDisplayString());
            if (!assetService.deleteAsset(getAssetId(device))) {
                LOG.warning("UPnP device gone but can't delete discovered asset: " + device);
            }
        }

    };

    @Override
    public String getProtocolName() {
        return PROTOCOL_NAME;
    }

    @Override
    public String getProtocolDisplayName() {
        return PROTOCOL_DISPLAY_NAME;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    protected void doLinkProtocolConfiguration(AssetAttribute protocolConfiguration) {
        boolean isEnabled = protocolConfiguration.isEnabled();
        if (!isEnabled) {
            updateStatus(protocolConfiguration.getReferenceOrThrow(), ConnectionStatus.DISABLED);
            return;
        }

        protocolConfiguration.getAssetId().ifPresent(agentId -> {

            // The parent is either the configured asset to group all discovered devices or the agent
            protocolConfigsGroupAssetId.put(
                protocolConfiguration.getReferenceOrThrow(),
                protocolConfiguration.getMetaItem(GROUP_ASSET_ID).flatMap(AbstractValueHolder::getValueAsString).orElse(agentId)
            );

            Collection<Device> devices = getService().getRegistry().getDevices();
            updateStatus(protocolConfiguration.getReferenceOrThrow(), ConnectionStatus.CONNECTED);

            if (devices.size() > 0) {
                for (Device device : getService().getRegistry().getDevices()) {
                    storeAssets(protocolConfiguration, device);
                }
            }
        });
    }

    @Override
    protected void doUnlinkProtocolConfiguration(AssetAttribute protocolConfiguration) {
        protocolConfigsGroupAssetId.remove(protocolConfiguration.getReferenceOrThrow());

        updateStatus(protocolConfiguration.getReferenceOrThrow(), ConnectionStatus.DISABLED);

        if (linkedProtocolConfigurations.size() == 1) {
            closeService();
        }
    }

    @Override
    protected void doLinkAttribute(AssetAttribute attribute, AssetAttribute protocolConfiguration) {


    }

    @Override
    protected void doUnlinkAttribute(AssetAttribute attribute, AssetAttribute protocolConfiguration) {

    }

    @Override
    protected void processLinkedAttributeWrite(AttributeEvent event, AssetAttribute protocolConfiguration) {

    }

    protected UpnpService getService() {
        if (INSTANCE == null) {
            INSTANCE = new UpnpServiceImpl(registryListener);
            INSTANCE.getControlPoint().search(new STAllHeader());
        }
        return INSTANCE;
    }

    protected void closeService() {
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
        }
    }

    protected void storeAssets(AssetAttribute protocolConfiguration, Device device) {
        String parentId = protocolConfigsGroupAssetId.get(protocolConfiguration.getReferenceOrThrow());
        LOG.fine("Storing UPnP device as child assets of " + parentId + ": " + device);
        Asset asset = createAsset(parentId, device);
        asset = assetService.mergeAsset(asset);
    }

    protected Asset createAsset(String parentId, Device device) {
        Asset asset = new Asset(
            device.getDisplayString(),
            AssetType.THING
        );
        asset.setParentId(parentId);
        asset.setId(getAssetId(device));

        long currentTime = timerService.getCurrentTimeMillis();
        Optional.ofNullable(device.getType().getDisplayString()).ifPresent(v ->
            asset.addAttributes(
                new AssetAttribute("type", STRING, Values.create(v), currentTime)
                    .addMeta(new MetaItem(LABEL, Values.create("Device Type")))
            )
        );

        Optional.ofNullable(device.getDetails().getManufacturerDetails().getManufacturer()).ifPresent(v ->
            asset.addAttributes(
                new AssetAttribute("manufacturer", STRING, Values.create(v), currentTime)
                    .addMeta(new MetaItem(LABEL, Values.create("Manufacturer")))
            )
        );

        Optional.ofNullable(device.getDetails().getFriendlyName()).ifPresent(v ->
            asset.addAttributes(
                new AssetAttribute("friendlyName", STRING, Values.create(v), currentTime)
                    .addMeta(new MetaItem(LABEL, Values.create("Friendly Name")))
            )
        );

        Optional.ofNullable(device.getDetails().getModelDetails().getModelNumber()).ifPresent(v ->
            asset.addAttributes(
                new AssetAttribute("modelNumber", STRING, Values.create(v), currentTime)
                    .addMeta(new MetaItem(LABEL, Values.create("Model Number")))
            )
        );

        Optional.ofNullable(device.getDetails().getModelDetails().getModelName()).ifPresent(v ->
            asset.addAttributes(
                new AssetAttribute("modelName", STRING, Values.create(v), currentTime)
                    .addMeta(new MetaItem(LABEL, Values.create("Model Name")))
            )
        );

        Optional.ofNullable(device.getDetails().getModelDetails().getModelDescription()).ifPresent(v ->
            asset.addAttributes(
                new AssetAttribute("modelDescription", STRING, Values.create(v), currentTime)
                    .addMeta(new MetaItem(LABEL, Values.create("Model Description")))
            )
        );

        Optional.ofNullable(device.getDetails().getSerialNumber()).ifPresent(v ->
            asset.addAttributes(
                new AssetAttribute("serialNumber", STRING, Values.create(v), currentTime)
                    .addMeta(new MetaItem(LABEL, Values.create("Serial Number")))
            )
        );

        return asset;
    }

    protected String getAssetId(Device device) {
        return Generators.nameBasedGenerator().generate(
            device.getIdentity().getUdn().getIdentifierString()
        ).toString();
    }
}
