package com.example.fhirviewer.model;

import java.nio.file.Path;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

/**
 * A FHIR resource that has been loaded from a file, a classpath sample or raw text,
 * together with the information needed to display it.
 *
 * <p>The resource itself is always the live HAPI model object, so edits made
 * through the editor are immediately visible to every view. The {@code dirty}
 * flag tracks whether the live model differs from what was last loaded or saved;
 * the UI uses it for the {@code *} title marker, the Save enablement and the
 * discard-changes guard.</p>
 */
public final class LoadedResource {

    private final IBaseResource resource;
    private String sourceName;
    private final String rawText;

    /**
     * The file the resource was loaded from, or that it was last saved to.
     * {@code null} for samples, pasted text and brand new resources.
     */
    private Path sourcePath;
    /** The format the resource was read in, or should be written in. */
    private ResourceFormat format;
    /** True once the live model has been changed since load/save. */
    private boolean dirty;

    public LoadedResource(IBaseResource resource, ResourceFormat format, String sourceName, String rawText) {
        this(resource, format, sourceName, rawText, null);
    }

    public LoadedResource(
            IBaseResource resource, ResourceFormat format, String sourceName, String rawText, Path sourcePath) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.format = Objects.requireNonNull(format, "format");
        this.sourceName = sourceName;
        this.rawText = rawText;
        this.sourcePath = sourcePath;
        this.dirty = false;
    }

    public IBaseResource getResource() {
        return resource;
    }

    /** The format the resource was read in (or was detected as). */
    public ResourceFormat getFormat() {
        return format;
    }

    /** Changes the format used for the next save (chosen via Save As). */
    public void setFormat(ResourceFormat format) {
        this.format = Objects.requireNonNull(format, "format");
    }

    /** A human readable name for the resource origin, for example <code>patient.json</code>. */
    public String getSourceName() {
        return sourceName;
    }

    /** Re-labels the resource after Save As, so the title bar shows the new file name. */
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    /** The original text as loaded, or {@code null} when the resource was not text based. */
    public String getRawText() {
        return rawText;
    }

    /** The file backing this resource, or {@code null} when there is none yet. */
    public Path getSourcePath() {
        return sourcePath;
    }

    /** Rebases this resource on a file, for example after Save As. */
    public void setSourcePath(Path sourcePath) {
        this.sourcePath = sourcePath;
    }

    /** True when the live model has unsaved changes. */
    public boolean isDirty() {
        return dirty;
    }

    /** Marks the resource as changed (called after every successful edit). */
    public void markDirty() {
        this.dirty = true;
    }

    /** Marks the resource as matching what is on disk (called after load/save). */
    public void markClean() {
        this.dirty = false;
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