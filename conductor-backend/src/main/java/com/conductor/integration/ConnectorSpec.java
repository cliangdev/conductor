package com.conductor.integration;

import java.util.List;

/**
 * Declarative description of how a connector is configured and instantiated.
 *
 * @param authType      primary credential-acquisition mode (drives the connect affordance)
 * @param singleInstance true => the hub shows one "Connect/Disconnect"; false => a list + "Add another"
 * @param fields        config fields rendered by the hub (user-input and generated)
 */
public record ConnectorSpec(AuthType authType, boolean singleInstance, List<ConnectorConfigField> fields) {

    public static ConnectorSpec apiKey(boolean singleInstance, List<ConnectorConfigField> fields) {
        return new ConnectorSpec(AuthType.API_KEY, singleInstance, fields);
    }

    public static ConnectorSpec oauth2(boolean singleInstance, List<ConnectorConfigField> fields) {
        return new ConnectorSpec(AuthType.OAUTH2, singleInstance, fields);
    }

    public static ConnectorSpec webhook(boolean singleInstance, List<ConnectorConfigField> fields) {
        return new ConnectorSpec(AuthType.WEBHOOK, singleInstance, fields);
    }
}
