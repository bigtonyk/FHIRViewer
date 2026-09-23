package com.example.fhirviewer.model;

/**
 * Represents a loaded FHIR NPM Implementation Guide package.
 */
public final class IgPackageInfo {
    private final String name;
    private final String version;
    private final String fhirVersion;
    private final String canonicalUrl;
    private final String description;

    public IgPackageInfo(String name, String version, String fhirVersion,
                         String canonicalUrl, String description) {
        this.name = name == null ? "" : name;
        this.version = version == null ? "" : version;
        this.fhirVersion = fhirVersion == null ? "" : fhirVersion;
        this.canonicalUrl = canonicalUrl == null ? "" : canonicalUrl;
        this.description = description == null ? "" : description;
    }

    public String name() { return name; }
    public String version() { return version; }
    public String fhirVersion() { return fhirVersion; }
    public String canonicalUrl() { return canonicalUrl; }
    public String description() { return description; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (!version.isEmpty()) sb.append(" ").append(version);
        if (!fhirVersion.isEmpty()) sb.append(" (").append(fhirVersion).append(")");
        return sb.toString();
    }
}