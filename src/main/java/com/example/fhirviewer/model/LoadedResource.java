package com.example.fhirviewer.model;

import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

/**
 * A FHIR resource that has been loaded from a file, a classpath sample or raw text,
 * together with the information needed to display it.
 */
public final class LoadedResource {

    private final IBaseResource resource;
    private final ResourceFormat format;
    private final String sourceName;
    private final String rawText;

    public LoadedResource(IBaseResource resource, ResourceFormat format, String sourceName, String rawText) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.format = Objects.requireNonNull(format, "format");
        this.sourceName = sourceName;
        this.rawText = rawText;
    }

    public IBaseResource getResource() {
        return resource;
    }

    /** The format the resource was read in (or was detected as). */
    public ResourceFormat getFormat() {
        return format;
    }

    /** A human readable name for the resource origin, for example <code>patient.json</code>. */
    public String getSourceName() {
        return sourceName;
    }

    /** The original text as loaded, or {@code null} when the resource was not text based. */
    public String getRawText() {
        return rawText;
    }

    /** The FHIR resource type, for example <code>Patient</code>. */
    public String getResourceType() {
        return resource.fhirType();
    }

    /** The logical id of the resource, or {@code null} when it has none. */
    public String getResourceId() {
        IIdType id = resource.getIdElement();
        if (id == null || !id.hasIdPart()) {
            return null;
        }
        return id.getIdPart();
    }

    /** True when this resource is a <code>Bundle</code>. */
    public boolean isBundle() {
        return "Bundle".equals(getResourceType());
    }

    /** A short label such as <code>Patient/12345</code>. */
    public String getDisplayName() {
        String id = getResourceId();
        return id == null ? getResourceType() : getResourceType() + "/" + id;
    }

    @Override
    public String toString() {
        return getDisplayName() + " (" + format.getDisplayName() + ")";
    }
}