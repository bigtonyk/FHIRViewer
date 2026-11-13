package com.spiraleyes.fhirviewer.service.scripting.api;

import java.util.Objects;

/**
 * Stable identity of one configured FHIR server. No credentials ever appear here.
 */
public record ServerInfo(
        String name,
        String baseUrl,
        String fhirVersion,
        String pluginId) {

    public ServerInfo {
        name = Objects.requireNonNull(name, "name");
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        fhirVersion = Objects.requireNonNull(fhirVersion, "fhirVersion");
        pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    @Override
    public String toString() {
        return name + " (" + baseUrl + ", FHIR " + fhirVersion + ", " + pluginId + ")";
    }
}
