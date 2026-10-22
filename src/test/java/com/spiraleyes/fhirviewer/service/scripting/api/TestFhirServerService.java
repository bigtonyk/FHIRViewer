package com.spiraleyes.fhirviewer.service.scripting.api;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.PatchFormat;
import com.spiraleyes.fhirviewer.server.SearchRequest;
import com.spiraleyes.fhirviewer.server.SearchResultPage;
import com.spiraleyes.fhirviewer.server.ServerCapabilities;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.server.ServerWriteResult;

/**
 * A {@link FhirServerService} whose read/search/capabilities answers the test
 * chooses, so the scripting API can be tested against a fake server instead of
 * the network.
 */
class TestFhirServerService extends FhirServerService {

    IBaseResource readResource;
    SearchResultPage searchPage = SearchResultPage.empty();
    ServerCapabilities capabilities = ServerCapabilities.empty();
    ServerOperationException readFailure;
    ServerOperationException searchFailure;
    ServerOperationException capabilitiesFailure;
    ServerWriteResult writeResult = ServerWriteResult.created("new-id", "v1");
    boolean supportsWrite = true;

    TestFhirServerService() {
        super(new FhirServerPluginRegistry());
    }

    @Override
    public IBaseResource read(FhirServerConfiguration server, String resourceType,
            String resourceId) throws ServerOperationException {
        if (readFailure != null) {
            throw readFailure;
        }
        return readResource;
    }

    @Override
    public SearchResultPage search(FhirServerConfiguration server, SearchRequest request)
            throws ServerOperationException {
        if (searchFailure != null) {
            throw searchFailure;
        }
        return searchPage;
    }

    @Override
    public ServerCapabilities capabilities(FhirServerConfiguration server)
            throws ServerOperationException {
        if (capabilitiesFailure != null) {
            throw capabilitiesFailure;
        }
        return capabilities;
    }

    @Override
    public ServerWriteResult create(FhirServerConfiguration server, IBaseResource resource)
            throws ServerOperationException {
        return writeResult;
    }

    @Override
    public ServerWriteResult update(FhirServerConfiguration server, IBaseResource resource,
            ServerOrigin origin) throws ServerOperationException {
        return writeResult;
    }

    int deleteCalls;
    int patchCalls;
    ServerWriteResult patchResult = ServerWriteResult.updated("patched-id", "v2");

    @Override
    public ServerWriteResult patch(FhirServerConfiguration server, ServerOrigin origin, String body,
            PatchFormat format) throws ServerOperationException {
        patchCalls++;
        return patchResult == null ? writeResult : patchResult;
    }

    @Override
    public void delete(FhirServerConfiguration server, ServerOrigin origin)
            throws ServerOperationException {
        deleteCalls++;
    }

    @Override
    public boolean supportsWrite(FhirServerConfiguration server) {
        return supportsWrite;
    }
}
