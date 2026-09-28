package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * What the server said after a successful create, update or delete.
 *
 * <p>Returned instead of nothing so the editor can rebase its {@link ServerOrigin} on
 * what the server actually stored. Servers are free to assign or change the id on a
 * write, and they report a new {@code meta.versionId} afterwards; keeping the local
 * copy's view of those in step is what makes the next write's {@code If-Match} check
 * meaningful.</p>
 *
 * @param resourceId the id the server now uses, or {@code null} after a delete
 * @param versionId  the {@code meta.versionId} the server now reports, or {@code null}
 *                   when it reported none
 * @param created    {@code true} when the server created the resource, {@code false}
 *                   when it updated an existing one
 */
public record ServerWriteResult(String resourceId, String versionId, boolean created) {

    public ServerWriteResult {
        // resourceId and versionId are legitimately null: a server may return no id for a
        // deleted resource, and not every server reports a versionId.
    }

    /** The server created this resource. */
    public static ServerWriteResult created(String resourceId, String versionId) {
        return new ServerWriteResult(Objects.requireNonNull(resourceId, "resourceId"), versionId, true);
    }

    /** The server updated this existing resource. */
    public static ServerWriteResult updated(String resourceId, String versionId) {
        return new ServerWriteResult(Objects.requireNonNull(resourceId, "resourceId"), versionId, false);
    }

    /** The server deleted this resource; nothing remains to track. */
    public static ServerWriteResult deleted() {
        return new ServerWriteResult(null, null, false);
    }

    /** True when the server reported no {@code versionId}, so conflict checking is not possible. */
    public boolean hasVersion() {
        return versionId != null && !versionId.isBlank();
    }

    /** Never includes anything secret: this record holds no credentials. */
    @Override
    public String toString() {
        return (created ? "created " : "updated ") + resourceId
                + (hasVersion() ? " (version " + versionId + ")" : "");
    }
}
