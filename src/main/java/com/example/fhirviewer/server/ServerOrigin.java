package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * Where a resource in the editor came from, and what the server last told us about it.
 *
 * <p>Held by {@code MainWindow} alongside the loaded resource rather than inside
 * {@code LoadedResource}, because that model is file-shaped: its {@code sourcePath}
 * means a path on disk, and a server origin is a different thing. Keeping the origin
 * out here also leaves the file-open, sample and paste paths untouched.</p>
 *
 * <p>The {@code versionId} is the server's {@code meta.versionId} at read time. It is
 * what makes a write safe: {@code StandardFhirRestPlugin} sends it back as
 * {@code If-Match} so the server can refuse the write if somebody else changed the
 * resource in the meantime.</p>
 *
 * @param pluginId  the plugin that served the resource, for example {@code firely}
 * @param baseUrl   the server it came from
 * @param resourceType the FHIR resource type, for example {@code Patient}. Needed to
 *                   address the resource on a later write, so it is part of the origin
 *                   rather than re-derived from the edited text, which may not parse.
 * @param resourceId the server-assigned id, or {@code null} for a resource that does
 *                   not exist on the server yet
 * @param versionId the {@code meta.versionId} last seen, or {@code null} when the
 *                  server did not report one (in which case no conflict check is possible)
 */
public record ServerOrigin(String pluginId, String baseUrl, String resourceType,
        String resourceId, String versionId) {

    public ServerOrigin {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(resourceType, "resourceType");
    }

    /** An origin for a resource that exists on the server. */
    public static ServerOrigin of(String pluginId, String baseUrl, String resourceType,
            String resourceId, String versionId) {
        return new ServerOrigin(pluginId, baseUrl, resourceType, resourceId, versionId);
    }

    /**
     * An origin for a resource that has not been saved yet, so it has no server id.
     * Writing it means a create rather than an update.
     */
    public static ServerOrigin unsaved(String pluginId, String baseUrl, String resourceType) {
        return new ServerOrigin(pluginId, baseUrl, resourceType, null, null);
    }

    /** True when this resource already exists on the server and can be updated. */
    public boolean isSaved() {
        return resourceId != null && !resourceId.isBlank();
    }

    /**
     * True when the server told us a {@code meta.versionId}, so a conditional write can
     * detect a conflicting change. False means "no conflict detection is possible" and
     * the UI must say so rather than implying the check happened.
     */
    public boolean hasVersion() {
        return versionId != null && !versionId.isBlank();
    }

    /** True when this origin points at the same server, regardless of which resource. */
    public boolean isSameServer(ServerOrigin other) {
        return other != null && pluginId.equals(other.pluginId) && baseUrl.equals(other.baseUrl);
    }

    /**
     * Returns a copy carrying the id and version the server just assigned, so the editor
     * keeps tracking the resource correctly after a successful write.
     *
     * <p>The {@code resourceType} is carried over unchanged: a write never renames the
     * type, and dropping it here would leave a later save unable to address the resource.
     * A {@code null} id in the result keeps the origin unsaved rather than inventing one.</p>
     */
    public ServerOrigin rebased(ServerWriteResult result) {
        Objects.requireNonNull(result, "result");
        return new ServerOrigin(pluginId, baseUrl, resourceType,
                result.resourceId(), result.versionId());
    }

    /**
     * Returns a copy with the version dropped, so the next write sends no {@code If-Match}.
     *
     * <p>Used only when the user has explicitly chosen to overwrite a conflict: keeping the
     * stale version would just produce the same {@code 412} again. The resource id is kept,
     * so this still addresses an update rather than accidentally creating a second copy.</p>
     */
    public ServerOrigin withoutVersion() {
        return new ServerOrigin(pluginId, baseUrl, resourceType, resourceId, null);
    }

    /** Never includes anything secret: this record holds no credentials. */
    @Override
    public String toString() {
        return pluginId + " " + baseUrl + "/" + (resourceId == null ? "(unsaved)" : resourceId);
    }
}
