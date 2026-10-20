package com.spiraleyes.fhirviewer.service.scripting.api;

import java.util.List;

/**
 * The objects a script may see, bound to top-level names in a JShell session.
 *
 * <p>Injection is deliberate: before a script runs, the coordinator publishes
 * these bindings for the duration of that run only, and {@link #preamble()}
 * emits the JShell snippets that declare {@code fhir}, {@code viewer},
 * {@code validation}, {@code server} and {@code log} as final variables. A
 * script then uses them directly, exactly as the plan illustrates:
 * <pre>{@code
 * var patient = fhir.read("Patient", "example-id");
 * if (patient != null) {
 *     viewer.showResource(patient);
 *     log.info("Patient loaded");
 * }
 * }</pre>
 *
 * <p>Bindings live on a thread-local so each run carries its own set; nothing
 * leaks between runs, sessions or concurrent consoles.
 */
public final class ScriptBindings {

    private static final ThreadLocal<ScriptBindings> current = new ThreadLocal<>();

    /**
     * The bindings of the run in flight, readable from any thread.
     *
     * <p>JShell evaluates a snippet on its own thread, not on the thread that
     * called {@code eval}, so a thread-local would be invisible exactly where
     * the preamble needs it. This field is the fallback the preamble reads;
     * it is written before the run and restored after it, and the coordinator
     * serializes runs on one worker, so at most one run is ever in flight.</p>
     */
    private static volatile ScriptBindings active;

    /** The bindings for the run on this thread, or {@code null} when none. */
    public static ScriptBindings current() {
        ScriptBindings bindings = current.get();
        if (bindings == null) {
            bindings = active;
        }
        if (bindings == null) {
            throw new IllegalStateException(
                    "No scripting bindings are bound to this run. Bind fhir, viewer, validation, "
                            + "server and log before running a script.");
        }
        return bindings;
    }

    /**
     * The bindings for the run on this thread, or {@code null} when none. Unlike
     * {@link #current()}, this never throws; it is used to save and restore the
     * previous bindings around a run.
     */
    public static ScriptBindings currentOrNull() {
        return current.get();
    }

    /**
     * The bindings of the run in flight, or {@code null} when none. Unlike
     * {@link #currentOrNull()}, this is not thread-local: it is what a thread
     * other than the caller's sees.
     */
    public static ScriptBindings activeOrNull() {
        return active;
    }

    /** Publishes {@code bindings} for the runs on this thread. {@code null} clears them. */
    public static void set(ScriptBindings bindings) {
        if (bindings == null) {
            current.remove();
        } else {
            current.set(bindings);
        }
    }

    /**
     * Publishes {@code bindings} for the run in flight, so that a thread other
     * than the caller's — the thread JShell evaluates on — can see them.
     * {@code null} clears them.
     */
    public static void setActive(ScriptBindings bindings) {
        active = bindings;
    }

    public final FhirApi fhir;
    public final ViewerApi viewer;
    public final ValidationApi validation;
    public final ServerApi server;
    public final LogApi log;

    public ScriptBindings(FhirApi fhir, ViewerApi viewer, ValidationApi validation,
            ServerApi server, LogApi log) {
        this.fhir = fhir;
        this.viewer = viewer;
        this.validation = validation;
        this.server = server;
        this.log = log;
    }

    /**
     * JShell snippets, evaluated before the user's source, that bind the
     * top-level names. The names resolve to the instances held here through
     * {@link #current()}, so the same bindings object serves a whole run.
     */
    public List<String> preamble() {
        String fhirApi = FhirApi.class.getName();
        String viewerApi = ViewerApi.class.getName();
        String validationApi = ValidationApi.class.getName();
        String serverApi = ServerApi.class.getName();
        String logApi = LogApi.class.getName();
        String bindings = ScriptBindings.class.getName();
        return List.of(
                "import org.hl7.fhir.instance.model.api.IBaseResource;",
                "import com.spiraleyes.fhirviewer.server.SearchCriterion;",
                "import com.spiraleyes.fhirviewer.server.ServerInteraction;",
                "import com.spiraleyes.fhirviewer.service.scripting.api.SearchResults;",
                "final " + fhirApi + " fhir = " + bindings + ".current().fhir;",
                "final " + viewerApi + " viewer = " + bindings + ".current().viewer;",
                "final " + validationApi + " validation = " + bindings + ".current().validation;",
                "final " + serverApi + " server = " + bindings + ".current().server;",
                "final " + logApi + " log = " + bindings + ".current().log;");
    }
}
