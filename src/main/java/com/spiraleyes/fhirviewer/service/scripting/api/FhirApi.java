package com.spiraleyes.fhirviewer.service.scripting.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseMetaType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.PatchFormat;
import com.spiraleyes.fhirviewer.server.SearchRequest;
import com.spiraleyes.fhirviewer.server.SearchCriterion;
import com.spiraleyes.fhirviewer.server.SearchResultPage;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.server.ServerWriteResult;

/**
 * Approved resource reads and searches against the selected server, plus a safe
 * subset of writes gated behind an explicit {@link WritePolicy}.
 *
 * <p>Reads are the default and always allowed. A script that wants to create,
 * update, patch or delete must run with an enabled write policy, otherwise every write
 * is refused with a clear message before any request is made.
 *
 * <p>The write policy is checked again for every operation. That makes turning
 * writes off in the console revoke an old {@code fhir} reference a script kept in
 * the interactive session, and refuses a write if the active server changed after
 * the grant was approved.
 *
 * <p>Network calls happen on whatever thread runs the script -- the console's
 * worker thread, never the JavaFX thread -- so a slow server freezes nothing but
 * the script itself. Errors surface as {@link ScriptApiException} with a human
 * readable message, and a missing resource is {@code null} rather than an
 * exception, matching {@code if (patient != null)} style scripts.
 */
public class FhirApi {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    private final WritePolicy writePolicy;

    public FhirApi(FhirServerService serverService, FhirServerManager serverManager,
            WritePolicy writePolicy) {
        this.serverService = Objects.requireNonNull(serverService, "serverService");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
        this.writePolicy = writePolicy == null ? WritePolicy.READ_ONLY : writePolicy;
    }

    /** Reads one resource by type and id, or {@code null} when the server says it does not exist. */
    public IBaseResource read(String resourceType, String resourceId) {
        require(resourceType, resourceId);
        FhirServerConfiguration server = activeServer("read");
        try {
            return serverService.read(server, resourceType, resourceId);
        } catch (ServerOperationException e) {
            if (e.kind() == ServerOperationException.Kind.NOT_FOUND) {
                return null;
            }
            throw new ScriptApiException("Cannot read " + resourceType + "/" + resourceId
                    + " from " + server.name() + ": " + e.getMessage(), e);
        }
    }

    /** Searches one resource type by name/value criteria, one page at a time. */
    public SearchResults search(String resourceType, SearchCriterion... criteria) {
        require(resourceType);
        FhirServerConfiguration server = activeServer("search");
        List<SearchCriterion> list = new ArrayList<>();
        for (SearchCriterion criterion : criteria == null ? new SearchCriterion[0] : criteria) {
            if (criterion != null) {
                list.add(criterion);
            }
        }
        try {
            SearchResultPage page = serverService.search(server,
                    new SearchRequest(resourceType, list, DEFAULT_PAGE_SIZE));
            return of(page);
        } catch (ServerOperationException e) {
            throw new ScriptApiException("Cannot search " + resourceType + " on " + server.name()
                    + ": " + e.getMessage(), e);
        }
    }

    /** Searches one resource type with a raw query string sent to the server as written. */
    public SearchResults searchRaw(String resourceType, String query) {
        require(resourceType);
        return search(resourceType, SearchCriterion.raw(query));
    }

    /** Fetches the page at a {@code next} or {@code previous} link returned by a search. */
    public SearchResults nextPage(String pageToken) {
        return pageAt("next page", pageToken);
    }

    /** Fetches the page at a {@code previous} link returned by a search. */
    public SearchResults previousPage(String pageToken) {
        return pageAt("previous page", pageToken);
    }

    /** Creates a resource on the server. Refused unless writes are enabled. */
    public ServerWriteResult create(IBaseResource resource) {
        writePolicy.requireWrite();
        Objects.requireNonNull(resource, "resource");
        String type = resource.fhirType();
        FhirServerConfiguration server = activeServer("create");
        writePolicy.requireWrite(server, "create", type);
        long started = System.nanoTime();
        try {
            ServerWriteResult result = serverService.create(server, resource);
            record(server, "create", type, result.resourceId(), result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started,
                    new WriteJournal.Undo("create", type, result.resourceId(),
                            result.versionId(), null));
            return result;
        } catch (ServerOperationException e) {
            throw failedWrite("create", type, null, null, "on", server, e, started);
        }
    }

    /** Updates a resource on the server. Refused unless writes are enabled. */
    public ServerWriteResult update(IBaseResource resource) {
        writePolicy.requireWrite();
        Objects.requireNonNull(resource, "resource");
        String type = resource.fhirType();
        IIdType id = resource.getIdElement();
        if (id == null || !id.hasIdPart()) {
            throw new ScriptApiException("Cannot update " + type + ": it has no server id.");
        }
        String resourceId = id.getIdPart();
        FhirServerConfiguration server = activeServer("update");
        writePolicy.requireWrite(server, "update", type);
        IBaseResource before = readBefore(server, type, resourceId);
        String versionId = null;
        IBaseMetaType meta = resource.getMeta();
        if (meta != null) {
            versionId = meta.getVersionId();
        }
        long started = System.nanoTime();
        try {
            ServerWriteResult result = serverService.update(server, resource,
                    ServerOrigin.of(server.pluginId(), server.baseUrl(), type, resourceId, versionId));
            record(server, "update", type, resourceId, result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started,
                    new WriteJournal.Undo("update", type, resourceId, result.versionId(), before));
            return result;
        } catch (ServerOperationException e) {
            throw failedWrite("update", type, resourceId, versionId, "on", server, e, started);
        }
    }

    /** Applies a partial update (patch) to one resource. Refused unless writes are enabled. */
    public ServerWriteResult patch(String resourceType, String resourceId, String body, PatchFormat format) {
        return patch(resourceType, resourceId, body, format, null);
    }

    /**
     * Applies a partial update with a known version. Passing the version sends
     * {@code If-Match}, so the server can refuse a patch against a stale copy.
     */
    public ServerWriteResult patch(String resourceType, String resourceId, String body,
            PatchFormat format, String versionId) {
        writePolicy.requireWrite();
        require(resourceType, resourceId);
        if (body == null || body.isBlank()) {
            throw new ScriptApiException("A patch body is required and must not be blank.");
        }
        Objects.requireNonNull(format, "format");
        FhirServerConfiguration server = activeServer("patch");
        writePolicy.requireWrite(server, "patch", resourceType);
        IBaseResource before = readBefore(server, resourceType, resourceId);
        String version = blankToNull(versionId);
        long started = System.nanoTime();
        try {
            ServerWriteResult result = serverService.patch(server,
                    ServerOrigin.of(server.pluginId(), server.baseUrl(), resourceType, resourceId, version),
                    body, format);
            record(server, "patch", resourceType, resourceId, result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started,
                    new WriteJournal.Undo("patch", resourceType, resourceId,
                            result.versionId(), before));
            return result;
        } catch (ServerOperationException e) {
            throw failedWrite("patch", resourceType, resourceId, version, "on", server, e, started);
        }
    }

    /** Deletes one resource by type and id. Refused unless writes are enabled. */
    public void delete(String resourceType, String resourceId) {
        delete(resourceType, resourceId, null);
    }

    /**
     * Deletes one resource with a known version. Passing the version sends
     * {@code If-Match}, so the server can refuse to delete a resource that changed
     * after it was read.
     */
    public void delete(String resourceType, String resourceId, String versionId) {
        writePolicy.requireWrite();
        require(resourceType, resourceId);
        FhirServerConfiguration server = activeServer("delete");
        writePolicy.requireWrite(server, "delete", resourceType);
        String version = blankToNull(versionId);
        String summary = "Delete " + resourceType + "/" + resourceId + " on " + server.name()
                + " (" + server.baseUrl() + ")"
                + (version == null ? "" : " version " + version);
        long started = System.nanoTime();
        if (!writePolicy.confirmDestructive(summary)) {
            record(server, "delete", resourceType, resourceId, version,
                    WriteJournal.Status.CANCELLED, "cancelled", started);
            throw new WriteDeniedException("Delete cancelled.");
        }
        // Read only after the confirmation, so the before-image is as close as possible
        // to the delete and a cancelled prompt costs no extra request.
        IBaseResource before = readBefore(server, resourceType, resourceId);
        try {
            serverService.delete(server,
                    ServerOrigin.of(server.pluginId(), server.baseUrl(), resourceType, resourceId, version));
            record(server, "delete", resourceType, resourceId, version,
                    WriteJournal.Status.SUCCEEDED, "", started,
                    new WriteJournal.Undo("delete", resourceType, resourceId, version, before));
        } catch (ServerOperationException e) {
            throw failedWrite("delete", resourceType, resourceId, version, "from", server, e, started);
        }
    }

    private SearchResults pageAt(String which, String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            throw new ScriptApiException("A " + which + " token is required.");
        }
        FhirServerConfiguration server = activeServer("search");
        try {
            SearchResultPage page = serverService.pageAt(server, pageToken);
            return of(page);
        } catch (ServerOperationException e) {
            throw new ScriptApiException("Cannot fetch " + which + " from " + server.name()
                    + ": " + e.getMessage(), e);
        }
    }

    private SearchResults of(SearchResultPage page) {
        return new SearchResults(page.resources(), page.total(), page.nextPageToken(),
                page.previousPageToken());
    }

    private FhirServerConfiguration activeServer(String operation) {
        return serverManager.active().orElseThrow(() -> new ScriptApiException(
                "No server is selected. Configure one in Tools > FHIR Servers... "
                        + "and select it before " + operation + "."));
    }

    /**
     * Records one write and, when the transport timed out, reports that its outcome is
     * unknown instead of presenting it as a clean failure a script could safely retry.
     */
    private ScriptApiException failedWrite(String operation, String resourceType, String resourceId,
            String versionId, String preposition, FhirServerConfiguration server,
            ServerOperationException failure, long started) {
        boolean unknown = failure.kind() == ServerOperationException.Kind.TIMEOUT;
        record(server, operation, resourceType, resourceId, versionId,
                unknown ? WriteJournal.Status.UNKNOWN : WriteJournal.Status.FAILED,
                failure.kind().name(), started);
        if (unknown) {
            return new WriteOutcomeUnknownException(operation, server, failure);
        }
        String target = resourceType + (resourceId == null ? "" : "/" + resourceId);
        return new ScriptApiException("Cannot " + operation + " " + target + " " + preposition
                + " " + server.name() + ": " + failure.getMessage(), failure);
    }

    private WriteJournal.Entry record(FhirServerConfiguration server, String operation,
            String resourceType, String resourceId, String versionId, WriteJournal.Status status,
            String detail, long started) {
        return record(server, operation, resourceType, resourceId, versionId, status, detail,
                started, null);
    }

    private WriteJournal.Entry record(FhirServerConfiguration server, String operation,
            String resourceType, String resourceId, String versionId, WriteJournal.Status status,
            String detail, long started, WriteJournal.Undo undo) {
        WriteJournal journal = writePolicy.journal();
        if (journal == null) {
            return null;
        }
        return journal.record(server, operation, resourceType, resourceId, versionId, status, detail,
                Duration.ofNanos(Math.max(0L, System.nanoTime() - started)), undo);
    }

    /**
     * Reads the resource immediately before a write so the console can offer a best-effort
     * undo. A read failure must never block the write the user approved: the undo is
     * optional, and a server that cannot answer this extra read may still accept the write.
     */
    private IBaseResource readBefore(FhirServerConfiguration server, String resourceType,
            String resourceId) {
        if (writePolicy.journal() == null || resourceType == null || resourceId == null) {
            return null;
        }
        try {
            return serverService.read(server, resourceType, resourceId);
        } catch (ServerOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void require(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new ScriptApiException("A value is required and must not be blank.");
            }
        }
    }
}
