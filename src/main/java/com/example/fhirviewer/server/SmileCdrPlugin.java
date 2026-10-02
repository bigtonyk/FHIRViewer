package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hl7.fhir.r4.model.CapabilityStatement;

import com.example.fhirviewer.server.rest.RestMethod;

import ca.uhn.fhir.context.FhirContext;

/**
 * Smile CDR plugin: identifies and configures Smile CDR, and adds Smile-specific
 * capability detection while delegating all standard FHIR REST operations to
 * {@link StandardFhirRestPlugin}.
 *
 * <p>This plugin is intentionally thin: it wraps a standard REST plugin and only
 * interprets the results after the generic layer has done the real work. All
 * metadata, search, paging and read mechanics come from {@code StandardFhirRestPlugin};
 * this class adds only what is specific to Smile CDR.</p>
 *
 * <p>Design principles (see the implementation plan):</p>
 * <ul>
 *   <li>Does NOT re-implement FHIR REST — delegates to the standard layer.</li>
 *   <li>Does NOT duplicate generic authentication — uses {@link ServerAuthentication}.</li>
 *   <li>Does NOT store credentials in configuration — secrets travel via {@link ServerSession}.</li>
 *   <li>Never logs tokens, Authorization headers, or PHI.</li>
 * </ul>
 */
public class SmileCdrPlugin extends StandardFhirRestPlugin {

                /** The id stored in server definitions served by this plugin. */
    public static final String PLUGIN_ID = "smile-cdr";

    private static final Logger log = LoggerFactory.getLogger(SmileCdrPlugin.class);

    /** Creates the plugin on the default R4 FHIR context. */

    public SmileCdrPlugin() {
        this(com.example.fhirviewer.fhir.FhirContextFactory.r4());
    }

    /** Creates the plugin on an explicit FHIR context (used by tests). */
    public SmileCdrPlugin(FhirContext context) {
        super(context);
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "Smile CDR";
    }

    @Override
    public String description() {
        return "Smile CDR FHIR Server (delegates to the standard FHIR REST layer).";
    }

    @Override
    public boolean supports(FhirServerConfiguration configuration) {
        if (configuration == null) {
            return false;
        }
        // When the configuration explicitly names this plugin, accept it regardless of
        // extra fields — the user has chosen Smile CDR manually.
        if (PLUGIN_ID.equals(configuration.pluginId())) {
            return supportedFhirVersions().contains(configuration.fhirVersion());
        }
        // Also auto-detect when the configuration is a Smile-specific configuration type.
        if (configuration instanceof SmileCdrConfiguration) {
            return supportedFhirVersions().contains(configuration.fhirVersion());
        }
        return false;
    }

    /**
     * Tests the connection using the standard REST layer, then performs a
     * lightweight Smile-specific check on the CapabilityStatement.
     *
     * <p>The standard layer already validates reachability and FHIR conformance;
     * this override adds Smile-specific interpretation afterwards so a failed
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
                        log.info("smile test connection reachable versions={} resourceTypes={}",
                    caps.fhirVersion(), caps.resourceTypes().size());
        }
        String message = standard.message();
        if (message == null || message.isBlank()) {
            message = "Connected to Smile CDR.";
        } else {
            message = "Connected to Smile CDR. " + message;
        }
        return ConnectionResult.reachable(message, caps);
    }

    /**
     * Reads the server's capabilities, then wraps the result as Smile-specific
     * so the UI can distinguish a Smile CDR server from a generic FHIR server.
     */
    @Override
    public ServerCapabilities capabilities(ServerSession session) throws ServerOperationException {
        ServerCapabilities base = super.capabilities(session);
        return new SmileServerCapabilities(base);
    }

    /**
     * Attempts to identify whether the given CapabilityStatement looks like it
     * came from a Smile CDR instance.
     *
     * <p>Detection is intentionally heuristic — it does not depend on a single
     * string, so it can evolve without breaking the "user can always select
     * Smile CDR manually" guarantee.</p>
     *
     * @param statement a parsed CapabilityStatement, or {@code null}
     * @return {@code true} when the statement carries evidence of Smile CDR
     */
    static boolean looksLikeSmileCdr(CapabilityStatement statement) {
        if (statement == null) {
            return false;
        }
        // Check the "software" element on the CapabilityStatement.
        if (statement.hasSoftware()) {
            String name = statement.getSoftware().getName();
            if (name != null && !name.isBlank()) {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("smile") || lower.contains("cdr")) {
                    return true;
                }
            }
        }
        // Check for the sm-core implementation guide URL in the CapabilityStatement.
        // Smile CDR often advertises itself through extensions or implementation guides.
                if (statement.hasImplementationGuide()) {
            for (org.hl7.fhir.r4.model.CanonicalType ig : statement.getImplementationGuide()) {
                if (ig != null && ig.getValue() != null
                        && ig.getValue().toLowerCase(java.util.Locale.ROOT).contains("smile")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Smile CDR's reindexing operations.
     *
     * <p><b>These run on the FHIR endpoint itself</b>, not on Smile's JSON Admin API. That
     * is the whole reason they can be declared at all: the Admin API lives on a separate
     * port (typically 9000, against a FHIR endpoint on 8000), and this application resolves
     * operation paths against the single base URL the user configured, so anything under
     * {@code admin-json} would 404. See {@code docs/plans/09_SECOND_BASE_URL_FOR_ADMIN_APIS.md}.
     * The reindex family is <em>not</em> affected, because Smile exposes it as a normal FHIR
     * operation — which also corrects an earlier assumption in that plan that Smile had no
     * addressable operations at all.</p>
     *
     * <p>From Smile's "Search Parameter Reindexing" documentation. Shapes taken from the
     * documented example URLs verbatim, because the difference between {@code $reindex} and
     * {@code $reindex-dryrun} is the difference between changing the server's index and only
     * reporting what would change.</p>
     */
    private static final List<ServerOperation> SMILE_OPERATIONS = List.of(
            ServerOperation.builder("$reindex", RestMethod.POST, "$reindex")
                    .displayName("Re-index resources (system)")
                    .description("Re-indexes the resources named by 'url', or every"
                            + " resource when no url is given. Answers 202 Accepted with a"
                            + " Content-Location to poll; the completed job reports a Bundle"
                            + " summarising what changed. Slow — run it in a quiet period.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .queryParameter("url", "A search to select what to re-index, for example"
                            + " Patient?. Re-indexes everything when omitted.", false)
                    .queryParameter("partitionId", "Restrict the job to a tenant partition."
                            + " Repeatable; use _ALL for every partition.", false)
                    .returns(ServerOperation.ResultKind.BUNDLE)
                    .build(),
            ServerOperation.builder("reindex-instance", RestMethod.POST,
                            "{resourceType}/{id}/$reindex")
                    .displayName("Re-index one resource")
                    .description("Re-indexes a single resource so a new or changed"
                            + " SearchParameter takes effect for it. Answers 202 Accepted"
                            + " with a Content-Location to poll. Use this to test a search"
                            + " parameter before re-indexing everything.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .pathParameter("resourceType", "The resource type, for example Observation.")
                    .pathParameter("id", "The logical id of the resource to re-index.")
                    .returns(ServerOperation.ResultKind.BUNDLE)
                    .build(),
            ServerOperation.builder("reindex-dryrun", RestMethod.GET,
                            "{resourceType}/{id}/$reindex-dryrun")
                    .displayName("Preview a re-index (dry run)")
                    .description("Simulates the re-index of one resource and reports which"
                            + " search parameters would change, without altering the server."
                            + " Safe to run at any time. Requires the FHIR Storage (RDBMS)"
                            + " module; a server without it answers that the operation is"
                            + " unknown.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .pathParameter("resourceType", "The resource type, for example Observation.")
                    .pathParameter("id", "The logical id of the resource to preview.")
                    .returns(ServerOperation.ResultKind.FHIR_RESOURCE)
                    .build(),
            ServerOperation.builder("$mark-all-resources-for-reindexing", RestMethod.GET,
                            "$mark-all-resources-for-reindexing")
                    .displayName("Mark all resources for re-indexing (deprecated)")
                    .description("Marks every resource as needing re-indexing, to be picked"
                            + " up later. Deprecated by Smile in favour of $reindex, which"
                            + " gives better control and a viewable job; offered only for"
                            + " servers still relying on it.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .returns(ServerOperation.ResultKind.OPERATION_OUTCOME)
                    .build(),
            // ---- The JSON Admin API ----
            //
            // UNVERIFIED against a running server. Every path below is taken from Smile's
            // own documentation, not from a live instance: there is no reachable public
            // Smile CDR, so these have never been sent anywhere. That is stated here rather
            // than only in the plan because a path that looks right and is wrong fails on
            // every real server while appearing entirely correct - which is exactly how
            // Firely's /administration branch went wrong on this branch once already.
            //
            // They are also the reason this phase exists: these are served from the JSON
            // Admin API on its own port, so each carries administration() and is only
            // reachable when the server has an administration URL configured.
            adminGet("version", "Version",
                    "The Smile CDR version this server is running.",
                    ServerOperation.ResultKind.JSON),
            adminGet("config", "System configuration",
                    "The server's own configuration, as JSON.",
                    ServerOperation.ResultKind.JSON),
            adminGet("runtime-status", "Runtime status",
                    "Uptime, memory and thread state.",
                    ServerOperation.ResultKind.JSON),
            adminGet("metrics", "Metrics",
                    "Runtime metrics. Large; useful when reporting a problem.",
                    ServerOperation.ResultKind.JSON),
            adminGet("openid-clients", "OpenID Connect clients",
                    "The OpenID Connect clients this server trusts.",
                    ServerOperation.ResultKind.JSON),
            adminGet("openid-sessions", "OpenID Connect sessions",
                    "Live OpenID Connect sessions.",
                    ServerOperation.ResultKind.JSON),
            adminGet("privacy-notice", "Privacy notice",
                    "The privacy notice this server publishes.",
                    ServerOperation.ResultKind.JSON),
            ServerOperation.builder("admin-user-list", RestMethod.GET,
                            "user-management/{node_id}/{module_id}/users")
                    .displayName("Users")
                    .description("The users of one node and module. Both ids come from the"
                            + " server's own configuration.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .administration()
                    .pathParameter("node_id", "The node id, from the server configuration.")
                    .pathParameter("module_id", "The module id, from the server configuration.")
                    .returns(ServerOperation.ResultKind.JSON)
                    .build(),
            ServerOperation.builder("admin-invalidate-sessions", RestMethod.POST,
                            "user-management/{node_id}/{module_id}/invalidate-all-sessions")
                    .displayName("Invalidate all sessions for a user")
                    .description("Revokes every live token for one user. The only mutating"
                            + " entry in this group: it signs people out immediately, so it is"
                            + " here deliberately and nothing else destructive is.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .requiresAuthentication()
                    .administration()
                    .pathParameter("node_id", "The node id, from the server configuration.")
                    .pathParameter("module_id", "The module id, from the server configuration.")
                    .queryParameter("username", "The user whose sessions are revoked.", true)
                    .returns(ServerOperation.ResultKind.JSON)
                    .build());

    /**
     * One read-only JSON Admin API call.
     *
     * <p>Every one of these needs the {@code ACCESS_ADMIN_JSON} permission, so
     * {@link ServerOperation.Builder#requiresAuthentication()} is set on all of them — which
     * also means a session without credentials is told so locally rather than by a 401 from
     * a server that had nothing better to say.</p>
     */
    private static ServerOperation adminGet(String id, String displayName, String description,
            ServerOperation.ResultKind returns) {
        return ServerOperation.builder(id, RestMethod.GET, id + "/")
                .displayName(displayName)
                .description(description)
                .category(ServerOperation.Category.ADMINISTRATION)
                .requiresAuthentication()
                .administration()
                .returns(returns)
                .build();
    }

    /**
     * Smile CDR's operations, which is what the generic operation screen lists for a
     * Smile server.
     *
     * <p>Declared as data like every other plugin's, and all of it sits on the FHIR
     * endpoint, so it is reachable with the base URL the user already configured.</p>
     *
     * <p>Concatenated with {@code super} so the inherited bulk export and import stay on the
     * list. A Smile server supports both, and overriding without them would quietly remove
     * them.</p>
     */
    @Override
    public List<ServerOperation> availableOperations() {
        return Stream.concat(super.availableOperations().stream(),
                SMILE_OPERATIONS.stream()).toList();
    }

    /**
     * Smile CDR's extra operations are reachable through the generic operation screen, so
     * there is no vendor screen to declare.
     *
     * <p>Returning the inherited empty list is honest here: unlike Firely, Smile's
     * additions are all callable endpoints rather than a whole administrative UI, so there
     * is nothing for a vendor screen to open.</p>
     */
    @Override
    public List<ServerVendorAction> vendorActions() {
        return List.of();
    }

    /**
     * A {@link ServerCapabilities} wrapper that tags the result as coming from
     * a Smile CDR server, so the UI can show Smile-specific options.
     */
    public static final class SmileServerCapabilities extends ServerCapabilities {

        public SmileServerCapabilities(ServerCapabilities delegate) {
            super(delegate);
        }

        /** True — this capability set was produced for a Smile CDR server. */
        public boolean isSmileCdr() {
            return true;
        }

        @Override
        public String toString() {
            return "Smile CDR " + super.toString();
        }
    }
}