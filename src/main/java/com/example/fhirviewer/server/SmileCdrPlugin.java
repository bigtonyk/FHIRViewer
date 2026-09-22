package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hl7.fhir.r4.model.CapabilityStatement;

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
     * A {@link ServerCapabilities} wrapper that tags the result as coming from
     * a Smile CDR server, so the UI can show Smile-specific options.
     */
    public static final class SmileServerCapabilities extends ServerCapabilities {

        public SmileServerCapabilities(ServerCapabilities delegate) {
            super(delegate.fhirVersion(), delegate.resourceTypes(), delegate.pagingSupported());
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