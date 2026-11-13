package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;
import com.spiraleyes.fhirviewer.service.scripting.ScriptingSession;

class ScriptBindingsInjectionTest {

    private ScriptingSession session;
    private ScriptBindings bindings;

    @BeforeEach
    void openSession() {
        session = new ScriptingSession();
        FhirService fhirService = new FhirService();
        FhirServerManager manager = new FhirServerManager();
        TestFhirServerService service = new TestFhirServerService();
        bindings = new ScriptBindings(
                new FhirApi(service, manager, WritePolicy.READ_ONLY),
                new ViewerApi(fhirService),
                new ValidationApi(fhirService),
                new ServerApi(service, manager),
                new LogApi());
    }

    @AfterEach
    void closeSession() {
        session.close();
    }

    @Test
    void allFiveNamesAreBoundToTheSuppliedObjects() {
        ScriptRunResult result = session.run(
                "fhir != null && viewer != null && validation != null && server != null && log != null",
                bindings);
        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals("true", first(result, ScriptRunResult.Kind.VALUE).text());
    }

    @Test
    void logOutputIsCapturedInTheResult() {
        ScriptRunResult result = session.run("log.info(\"bound and working\"); \"ok\"", bindings);
        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertTrue(result.entries().stream()
                .anyMatch(e -> e.kind() == ScriptRunResult.Kind.OUTPUT
                        && e.text().equals("[INFO] bound and working")),
                "expected the log line in the output: " + result.entries());
        // JShell renders a string value with its quotes, as the REPL does.
        assertEquals("\"ok\"", first(result, ScriptRunResult.Kind.VALUE).text());
    }

    @Test
    void serverReportsNoActiveServerWithoutConfiguration() {
        ScriptRunResult result = session.run("server.activeServer() == null", bindings);
        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals("true", first(result, ScriptRunResult.Kind.VALUE).text());
    }

    @Test
    void fhirIsBoundToTheFhirApi() {
        ScriptRunResult result = session.run("fhir.getClass().getName()", bindings);
        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertTrue(first(result, ScriptRunResult.Kind.VALUE).text().contains("FhirApi"),
                "expected the FhirApi class name: " + result.entries());
    }

    @Test
    void namesAreUndefinedWithoutBindings() {
        ScriptRunResult result = session.run("fhir", null);
        assertEquals(ScriptRunResult.Status.COMPLETED_WITH_ERRORS, result.status());
        assertTrue(result.entries().stream()
                .anyMatch(e -> e.kind() == ScriptRunResult.Kind.DIAGNOSTIC
                        && e.text().contains("cannot find symbol")),
                "expected an undefined-symbol diagnostic: " + result.entries());
    }

    private static ScriptRunResult.Entry first(ScriptRunResult result,
            ScriptRunResult.Kind kind) {
        ScriptRunResult.Entry entry = result.first(kind);
        assertNotNull(entry, "expected an entry of kind " + kind + " in " + result.entries());
        return entry;
    }
}
