package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;

/**
 * What a plugin reports about a server after a connection test or a metadata fetch.
 *
 * <p>This is the capability data the UI needs: the FHIR version the server speaks, the
 * resource types it supports and the search parameters it advertises. Raw HAPI
 * CapabilityStatement handling stays inside the plugins; callers use this instead.</p>
 */
public final class ServerCapabilities {

    private final String fhirVersion;
    private final List<String> resourceTypes;
    private final boolean pagingSupported;

    public ServerCapabilities(String fhirVersion, List<String> resourceTypes, boolean pagingSupported) {
        this.fhirVersion = Objects.requireNonNull(fhirVersion, "fhirVersion");
        this.resourceTypes = List.copyOf(Objects.requireNonNull(resourceTypes, "resourceTypes"));
        this.pagingSupported = pagingSupported;
    }

    /** The FHIR version the server reported, for example <code>R4</code>. */
    public String fhirVersion() {
        return fhirVersion;
    }

    /** The resource types the server supports, in server order. Never {@code null}. */
    public List<String> resourceTypes() {
        return resourceTypes;
    }

    /** True when the server returned paging links with its results. */
    public boolean pagingSupported() {
        return pagingSupported;
    }

    /** An empty capability set, used when a server answers but reports nothing usable. */
    public static ServerCapabilities empty() {
        return new ServerCapabilities("", List.of(), false);
    }

    @Override
    public String toString() {
        return "FHIR " + fhirVersion + ", " + resourceTypes.size() + " resource types"
                + (pagingSupported ? ", paging" : "");
    }
}
