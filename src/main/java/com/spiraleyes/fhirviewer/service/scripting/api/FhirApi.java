package com.spiraleyes.fhirviewer.service.scripting.api;

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
 * update or delete must run with an enabled write policy, otherwise every write
 * is refused with a clear message before any request is made.
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
    private final WritePolicy.Confirmation confirmation;

    public FhirApi(FhirServerService serverService, FhirServerManager serverManager,
            WritePolicy writePolicy) {
        this(serverService, serverManager, writePolicy, null);
    }

    public FhirApi(FhirServerService serverService, FhirServerManager serverManager,
            WritePolicy writePolicy, WritePolicy.Confirmation confirmation) {
        this.serverService = Objects.requireNonNull(serverService, "serverService");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
        this.writePolicy = writePolicy == null ? WritePolicy.READ_ONLY : writePolicy;
        this.confirmation = confirmation;
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
        FhirServerConfiguration server = activeServer("create");
        try {
            return serverService.create(server, resource);
        } catch (ServerOperationException e) {
            throw new ScriptApiException("Cannot create " + resource.fhirType() + " on "
                    + server.name() + ": " + e.getMessage(), e);
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
        FhirServerConfiguration server = activeServer("update");
        String versionId = null;
        IBaseMetaType meta = resource.getMeta();
        if (meta != null) {
            versionId = meta.getVersionId();
        }
        try {
            return serverService.update(server, resource,
                    ServerOrigin.of(server.pluginId(), server.baseUrl(), type, id.getIdPart(), versionId));
        } catch (ServerOperationException e) {
            throw new ScriptApiException("Cannot update " + type + "/" + id.getIdPart()
                    + " on " + server.name() + ": " + e.getMessage(), e);
        }
    }

    /** Applies a partial update (patch) to one resource. Refused unless writes are enabled. */
    public ServerWriteResult patch(String resourceType, String resourceId, String body, PatchFormat format) {
        writePolicy.requireWrite();
        require(resourceType, resourceId);
        if (body == null || body.isBlank()) {
            throw new ScriptApiException("A patch body is required and must not be blank.");
        }
        Objects.requireNonNull(format, "format");
        FhirServerConfiguration server = activeServer("patch");
        try {
            return serverService.patch(server,
                    ServerOrigin.of(server.pluginId(), server.baseUrl(), resourceType, resourceId, null),
                    body, format);
        } catch (ServerOperationException e) {
            throw new ScriptApiException("Cannot patch " + resourceType + "/" + resourceId
                    + " on " + server.name() + ": " + e.getMessage(), e);
        }
    }

    /** Deletes one resource by type and id. Refused unless writes are enabled. */
    public void delete(String resourceType, String resourceId) {
        writePolicy.requireWrite();
        require(resourceType, resourceId);
        FhirServerConfiguration server = activeServer("delete");
        String summary = "Delete " + resourceType + "/" + resourceId + " on " + server.name();
        WritePolicy.Confirmation handler = writePolicy.confirmation() != null
                ? writePolicy.confirmation() : confirmation;
        if (handler != null && !handler.confirm(summary)) {
            throw new WriteDeniedException("Delete cancelled.");
        }
        try {
            serverService.delete(server,
                    ServerOrigin.of(server.pluginId(), server.baseUrl(), resourceType, resourceId, null));
        } catch (ServerOperationException e) {
            throw new ScriptApiException("Cannot delete " + resourceType + "/" + resourceId
                    + " from " + server.name() + ": " + e.getMessage(), e);
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

    private static void require(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new ScriptApiException("A value is required and must not be blank.");
            }
        }
    }
}
