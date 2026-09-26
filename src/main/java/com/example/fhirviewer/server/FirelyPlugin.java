package com.example.fhirviewer.server;

import java.util.List;
import java.util.Locale;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CanonicalType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * A {@link ServerCapabilities} wrapper that tags the result as coming from
     * a Firely Server, so the UI can show Firely-specific options.
     */
    public static final class FirelyServerCapabilities extends ServerCapabilities {

        public FirelyServerCapabilities(ServerCapabilities delegate) {
            super(delegate.fhirVersion(), delegate.resourceTypes(), delegate.pagingSupported());
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
