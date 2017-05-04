package org.openremote.test.agent

import org.openremote.agent3.protocol.simulator.SimulatorProtocol
import org.openremote.manager.server.asset.AssetProcessingService
import org.openremote.manager.server.asset.AssetStorageService
import org.openremote.manager.server.setup.SetupService
import org.openremote.manager.server.setup.builtin.ManagerDemoSetup
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.ColorRGB
import org.openremote.model.value.ArrayValue
import org.openremote.model.value.ValueType
import org.openremote.model.value.Values
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class AgentDeploymentTest extends Specification implements ManagerContainerTrait {

    def "Check agent and thing deployment"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 30, initialDelay: 1)

        when: "the demo agent and thing have been deployed"
        def serverPort = findEphemeralPort()
        def container = startContainerWithoutDemoRules(defaultConfig(serverPort), defaultServices())
        def managerDemoSetup = container.getService(SetupService.class).getTaskOfType(ManagerDemoSetup.class)
        def simulatorProtocol = container.getService(SimulatorProtocol.class)
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)

        then: "the simulator elements should have the initial state"
        conditions.eventually {
            assert simulatorProtocol.getState(managerDemoSetup.thingId, "light1Toggle").asBoolean()
            assert simulatorProtocol.getState(managerDemoSetup.thingId, "light1Dimmer") == null
            // No initial value!
            assert new ColorRGB(simulatorProtocol.getState(managerDemoSetup.thingId, "light1Color") as ArrayValue) == new ColorRGB(88, 123, 88)
            assert Values.getNumber(simulatorProtocol.getState(managerDemoSetup.thingId, "light1PowerConsumption")).orElse(null) == 12.345d
        }

        when: "a client wants to change a thing attributes' value, triggering an actuator"
        conditions = new PollingConditions(timeout: 3, initialDelay: 1)
        def light1DimmerChange = new AttributeEvent(
                managerDemoSetup.thingId, "light1Dimmer", Values.create(66)
        )
        assetProcessingService.sendAttributeEvent(light1DimmerChange)

        then: "the simulator state and thing attribute value should be updated (simulator reflects actuator write as sensor read)"
        conditions.eventually {
            assert simulatorProtocol.getState(managerDemoSetup.thingId, "light1Dimmer") != null
            assert Values.getNumber(simulatorProtocol.getState(managerDemoSetup.thingId, "light1Dimmer")).orElse(null) == 66d
            def thing = assetStorageService.find(managerDemoSetup.thingId, true)
            def attribute = thing.getAttribute("light1Dimmer")
            assert attribute.get().getValue().isPresent()
            assert attribute.get().getValue().get().getType() == ValueType.NUMBER
            assert attribute.get().getValueAsInteger().isPresent()
            assert attribute.get().getValueAsInteger().get() == 66
        }

        when: "a simulated sensor changes its value"
        conditions = new PollingConditions(timeout: 3, initialDelay: 1)
        simulatorProtocol.putState(managerDemoSetup.thingId, "light1Dimmer", Values.create(77))

        then: "the thing attribute value should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerDemoSetup.thingId, true)
            def attribute = thing.getAttribute("light1Dimmer")
            assert attribute.get().getValue().isPresent()
            assert attribute.get().getValue().get().getType() == ValueType.NUMBER
            assert attribute.get().getValueAsInteger().isPresent()
            assert attribute.get().getValueAsInteger().get() == 77
        }

        cleanup: "the server should be stopped"
        stopContainer(container)
    }
}
