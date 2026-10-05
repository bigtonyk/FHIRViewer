package com.example.fhirviewer.server;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CanonicalType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fhirviewer.server.rest.RestMethod;

import ca.uhn.fhir.context.FhirContext;

/**
 * Firely plugin: identifies and configures Firely Server, and adds Firely-specific
 * capability detection while delegating all standard FHIR REST operations to
 * {@link StandardFhirRestPlugin}.
 *
 * <p>Firely Server is a distinct product from Smile CDR (both are Firely companies, and
 * both are built on HAPI FHIR). This plugin therefore keeps its own id, its own
 * configuration type and its own detection heuristic, and does not overlap with
 * {@link SmileCdrPlugin}.</p>
 *
 * <p>This plugin is intentionally thin: it wraps a standard REST plugin and only
 * interprets the results after the generic layer has done the real work. All
 * metadata, search, paging and read mechanics come from {@code StandardFhirRestPlugin};
 * this class adds only what is specific to Firely Server.</p>
 *
 * <p>Design principles (mirroring the Smile CDR plugin):</p>
 * <ul>
 *   <li>Does NOT re-implement FHIR REST — delegates to the standard layer.</li>
 *   <li>Does NOT duplicate generic authentication — uses {@link ServerAuthentication}.</li>
 *   <li>Does NOT store credentials in configuration — secrets travel via {@link ServerSession}.</li>
 *   <li>Never logs tokens, Authorization headers, or PHI.</li>
 * </ul>
 */
public class FirelyPlugin extends StandardFhirRestPlugin {

    /** The id stored in server definitions served by this plugin. */
    public static final String PLUGIN_ID = "firely";

    private static final Logger log = LoggerFactory.getLogger(FirelyPlugin.class);

    /** Creates the plugin on the default R4 FHIR context. */
    public FirelyPlugin() {
        this(com.example.fhirviewer.fhir.FhirContextFactory.r4());
    }

    /** Creates the plugin on an explicit FHIR context (used by tests). */
    public FirelyPlugin(FhirContext context) {
        super(context);
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "Firely Server";
    }

    @Override
    public String description() {
        return "Firely Server (delegates to the standard FHIR REST layer).";
    }

    @Override
    public boolean supports(FhirServerConfiguration configuration) {
        if (configuration == null) {
            return false;
        }
        // When the configuration explicitly names this plugin, accept it regardless of
        // extra fields — the user has chosen Firely Server manually.
        if (PLUGIN_ID.equals(configuration.pluginId())) {
            return supportedFhirVersions().contains(configuration.fhirVersion());
        }
        // Also auto-detect when the configuration is a Firely-specific configuration type.
        if (configuration instanceof FirelyConfiguration) {
            return supportedFhirVersions().contains(configuration.fhirVersion());
        }
        return false;
    }

    /**
     * Tests the connection using the standard REST layer, then performs a
     * lightweight Firely-specific check on the CapabilityStatement.
     *
     * <p>The standard layer already validates reachability and FHIR conformance;
     * this override adds Firely-specific interpretation afterwards so a failed
     * standard connection still surfaces as a clean failed result.</p>
     */
    @Override
    public ConnectionResult testConnection(ServerSession session) {
        ConnectionResult standard = super.testConnection(session);
        if (!standard.isReachable()) {
            return standard;
        }
        ServerCapabilities caps = standard.capabilities();
        if (caps != null) {
            log.info("firely test connection reachable versions={} resourceTypes={}",
                    caps.fhirVersion(), caps.resourceTypes().size());
        }
        String message = standard.message();
        if (message == null || message.isBlank()) {
            message = "Connected to Firely Server.";
        } else {
            message = "Connected to Firely Server. " + message;
        }
        return ConnectionResult.reachable(message, caps);
    }

    /**
     * Reads the server's capabilities, then wraps the result as Firely-specific
     * so the UI can distinguish a Firely Server from a generic FHIR server.
     */
    @Override
    public ServerCapabilities capabilities(ServerSession session) throws ServerOperationException {
        ServerCapabilities base = super.capabilities(session);
        return new FirelyServerCapabilities(base);
    }

    /**
     * Attempts to identify whether the given CapabilityStatement looks like it
     * came from a Firely Server instance.
     *
     * <p>Detection is intentionally heuristic — it does not depend on a single
     * string, so it can evolve without breaking the "user can always select
     * Firely Server manually" guarantee. Smile CDR is deliberately NOT matched
     * here, even though both are Firely products, because they are separately
     * deployable servers with different capabilities.</p>
     *
     * @param statement a parsed CapabilityStatement, or {@code null}
     * @return {@code true} when the statement carries evidence of Firely Server
     */
    static boolean looksLikeFirely(CapabilityStatement statement) {
        if (statement == null) {
            return false;
        }
        // Check the "software" element on the CapabilityStatement.
        if (statement.hasSoftware()) {
            String name = statement.getSoftware().getName();
            if (name != null && !name.isBlank()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("firely")) {
                    return true;
                }
            }
        }
        // Check the advertised implementation guides for a Firely-owned canonical URL.
        if (statement.hasImplementationGuide()) {
            for (CanonicalType ig : statement.getImplementationGuide()) {
                if (ig != null && ig.getValue() != null
                        && ig.getValue().toLowerCase(Locale.ROOT).contains("firely")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Firely Server's administration operations, resolved against the server's base URL.
     *
     * <p>Verified against a live Firely Server (6.10.0, FHIR R4): the
     * {@code /administration} branch answers {@code SearchParameter} searches, and both
     * {@code $reindex} and {@code $preload} exist there.</p>
     *
     * <p><b>The branch is {@code administration}, not {@code admin}.</b> That is the whole
     * Firely administration API, and getting it wrong would produce operations that fail
     * on every real server while looking entirely plausible. It is also CRUD on FHIR
     * resources rather than bespoke JSON endpoints, which is why most of these are
     * searches over conformance resources rather than vendor calls.</p>
     */
    private static final List<ServerOperation> ADMIN_OPERATIONS = List.of(
            ServerOperation.builder("$reindex", RestMethod.POST, "administration/$reindex")
                    .displayName("Re-index resources")
                    .description("Re-indexes resources so a new or changed SearchParameter "
                            + "takes effect. Slow; run it during a quiet period.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .returns(ServerOperation.ResultKind.OPERATION_OUTCOME)
                    .build(),
            ServerOperation.builder("$reindex-all", RestMethod.POST, "administration/$reindex-all")
                    .displayName("Re-index all resources")
                    .description("Re-indexes every resource rather than only the affected "
                            + "types. Considerably slower than $reindex.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .returns(ServerOperation.ResultKind.OPERATION_OUTCOME)
                    .build(),
            ServerOperation.builder("$preload", RestMethod.POST, "administration/$preload")
                    .displayName("Preload resources")
                    .description("Loads resources into the index ahead of time so the first "
                            + "read of a known resource is fast.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .returns(ServerOperation.ResultKind.OPERATION_OUTCOME)
                    .build(),
            ServerOperation.builder("$import-resources", RestMethod.POST,
                            "administration/$import-resources")
                    .displayName("Import conformance resources")
                    .description("Loads conformance resources into the administration API on"
                            + " demand, so they are available without a restart. Reads from the"
                            + " conformance resource repository configured on the server.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .returns(ServerOperation.ResultKind.OPERATION_OUTCOME)
                    .build(),
            ServerOperation.builder("$reset", RestMethod.POST, "administration/$reset")
                    .displayName("Reset the database")
                    .description("Erases the administration database and reloads it from "
                            + "scratch. Destroys stored conformance resources.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .returns(ServerOperation.ResultKind.OPERATION_OUTCOME)
                    .build(),
            searchConformanceResources("SearchParameter"),
            searchConformanceResources("StructureDefinition"),
            searchConformanceResources("ValueSet"),
            searchConformanceResources("CodeSystem"),
            searchConformanceResources("CompartmentDefinition"),
            searchConformanceResources("StructureMap"),
            searchConformanceResources("ConceptMap"),
            searchConformanceResources("Library"),
            searchConformanceResources("Measure"),
            searchConformanceResources("Questionnaire"),
            searchConformanceResources("Subscription"));

    /**
     * Firely's clinical quality measure operations.
     *
     * <p><b>These run on the main FHIR endpoint, not the administration API</b>, so they are
     * declared at the base URL rather than under {@code administration/}. They are listed
     * separately for that reason, and a test asserts the two groups never drift into each
     * other: a {@code administration/} prefix on a measure evaluation would 404, and a
     * missing prefix would send a write to the administration database.</p>
     *
     * <p>From Firely's "Executing Digital Quality Measures" documentation. Available when
     * the DQM module is licensed and deployed; a server without it answers with an
     * OperationOutcome saying the operation is unknown, which is a server setting rather
     * than a mistake here.</p>
     */
    private static final List<ServerOperation> DQM_OPERATIONS = List.of(
            ServerOperation.builder("$cql", RestMethod.POST, "$cql")
                    .displayName("Evaluate CQL")
                    .description("Evaluates a CQL expression against the resources on the"
                            + " server and returns the result. Requires a CQL expression in"
                            + " the request body.")
                    .category(ServerOperation.Category.VENDOR)
                    .requiresAuthentication()
                    .requiresBody("application/fhir+json")
                    .returns(ServerOperation.ResultKind.FHIR_RESOURCE)
                    .build(),
            ServerOperation.builder("$evaluate-measure", RestMethod.POST, "$evaluate-measure")
                    .displayName("Evaluate a measure")
                    .description("Evaluates a quality measure and returns a MeasureReport."
                            + " Requires a Measure reference in the request body.")
                    .category(ServerOperation.Category.VENDOR)
                    .requiresAuthentication()
                    .requiresBody("application/fhir+json")
                    .returns(ServerOperation.ResultKind.FHIR_RESOURCE)
                    .build(),
            ServerOperation.builder("$data-requirements", RestMethod.POST, "$data-requirements")
                    .displayName("Extract data requirements")
                    .description("Returns the data a measure or library needs, as a Library"
                            + " of DataRequirement resources. Useful before collecting data"
                            + " for a measure.")
                    .category(ServerOperation.Category.VENDOR)
                    .requiresAuthentication()
                    .requiresBody("application/fhir+json")
                    .returns(ServerOperation.ResultKind.FHIR_RESOURCE)
                    .build(),
            ServerOperation.builder("$evaluate", RestMethod.POST, "$evaluate")
                    .displayName("Evaluate an expression")
                    .description("Evaluates a FHIRPath or CQL expression supplied in the"
                            + " request body and returns the result. A general-purpose"
                            + " calculator for trying expressions against real data.")
                    .category(ServerOperation.Category.VENDOR)
                    .requiresAuthentication()
                    .requiresBody("application/fhir+json")
                    .returns(ServerOperation.ResultKind.FHIR_RESOURCE)
                    .build());

    /**
     * A search over one conformance resource type on the administration API.
     *
     * <p>Built rather than written out eleven times so the entries cannot drift apart in
     * shape and adding a type is one line. {@code _count} is offered because the
     * administration API holds a great many of these — a Firely Server in practice
     * carries well over a thousand SearchParameters — and without it the user gets an
     * arbitrary cut-off with no way to ask for more.</p>
     *
     * <p>Read-only on purpose. The administration API does support create, update and
     * delete on these types, and they are deliberately not offered: writing conformance
     * resources changes what a server validates against, which is not a thing to expose
     * as a one-click form beside a read.</p>
     */
    private static ServerOperation searchConformanceResources(String resourceType) {
        return ServerOperation.builder("list-" + resourceType, RestMethod.GET,
                        "administration/" + resourceType)
                .displayName("List " + resourceType + " (admin)")
                .description("Searches the " + resourceType + " resources held on the Firely"
                        + " administration API. Read-only.")
                .category(ServerOperation.Category.ADMINISTRATION)
                .requiresAuthentication()
                .queryParameter("_count", "How many to return per page.", false)
                .queryParameter("name", "Filter by name, for example a profile or parameter name.",
                        false)
                .returns(ServerOperation.ResultKind.BUNDLE)
                .build();
    }

    /**
     * Firely's administration operations, which is what the generic operation screen lists
     * for a Firely server.
     *
     * <p>The fine-grained counterpart to {@link #vendorActions()}: where a vendor action is
     * a whole screen, these are callable endpoints a form can be generated for. Every one
     * is marked as needing credentials, so a locked session says why the list is unusable
     * rather than leaving the user to work it out.</p>
     *
     * <p>Concatenated with {@code super} rather than replacing it: bulk export and import
     * are inherited from the standard layer, and overriding without them would quietly
     * remove them from a Firely server's list.</p>
     */
    @Override
    public List<ServerOperation> availableOperations() {
        return Stream.concat(super.availableOperations().stream(),
                Stream.concat(ADMIN_OPERATIONS.stream(), DQM_OPERATIONS.stream())).toList();
    }

    /**
     * Firely Server exposes administration endpoints beyond the FHIR REST API, so this
     * plugin declares a vendor screen for them. The declaration is live and the main UI
     * lists it; opening it reports {@code UNSUPPORTED} until the screen is written.
     *
     * <p>This is the reference example of the vendor-tooling seam: a plugin describes what
     * it can offer as data, and the application decides how to show it.</p>
     */
    @Override
    public List<ServerVendorAction> vendorActions() {
        return List.of(ServerVendorAction.available(
                "firely-admin",
                "Firely Administration...",
                "Firely-specific administration operations such as audit log and session control."));
    }


    /**
     * A {@link ServerCapabilities} wrapper that tags the result as coming from
     * a Firely Server, so the UI can show Firely-specific options.
     *
     * <p>The interaction and per-type detail is copied as well as the three headline
     * fields. Copying only those would present a Firely Server as supporting nothing
     * beyond the bare version string, which is both wrong and worse than the un-tagged
     * result the wrapper is decorating.
     */
    public static final class FirelyServerCapabilities extends ServerCapabilities {

        public FirelyServerCapabilities(ServerCapabilities delegate) {
            super(delegate);
        }

        /** True — this capability set was produced for a Firely Server. */
        public boolean isFirely() {
            return true;
        }

        @Override
        public String toString() {
            return "Firely Server " + super.toString();
        }
    }
}
