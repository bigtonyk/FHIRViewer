package com.spiraleyes.fhirviewer.service.scripting;
import java.util.List;

/**
 * A plugin that provides scripting capabilities for FHIRViewer.
 * <p>Plugins implementing this interface can advertise scriptable capabilities,
 * register typed API objects/operations, and make their features discoverable
 * through the scripting console help/UI. The core scripting infrastructure
 * remains independent of vendor-specific implementation classes.</p>
 * <p>Implementations should provide:</p>
 * <ul>
 *   <li>Descriptions and documented argument/return types for each operation</li>
 *   <li>Mutability information (read-only vs mutating)</li>
 *   <li>Compatibility prerequisites (FHIR version, required plugins)</li>
 *   <li>Prerequisite checks for plugin-dependent features</li>
 * </ul>
 * <p>This interface extends {@link FhirServerPlugin} to leverage the existing
 * plugin registration and server integration mechanism.</p>
 */
public interface ScriptingPlugin extends com.spiraleyes.fhirviewer.server.FhirServerPlugin {

    /** Returns a human-readable description of this plugin's scripting capabilities.
     * @return description of scripting features, or {@code null} if none
     */
    default String scriptingDescription() {
        return null;
    }

    /** Returns the scripting API objects provided by this plugin, or {@code null} if none.
     * @return mapping of binding name to API object, or {@code null}
     */
    default com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings scriptingApiObjects() {
        return null;
    }

    /** Checks whether this plugin's scripting features are available given the current server state.
     * @param configuration the server configuration
     * @return {@code true} if scripting features are available, {@code false} otherwise
     */
    default boolean supportsScripting(com.spiraleyes.fhirviewer.server.FhirServerConfiguration configuration) {
        return true;
    }

    /** Returns example scripts demonstrating this plugin's scripting capabilities.
     * @return list of example .jsh scripts, or empty list if none
     */
    default List<String> scriptingExamples() {
        return java.util.Collections.emptyList();
    }

    /** Provides the JShell preamble snippets that declare plugin-specific bindings.
     * These are evaluated before the user's script to bind plugin APIs.
     * @return list of JShell snippet strings, or empty list if none
     */
    default List<String> scriptingPreamble() {
        return java.util.Collections.emptyList();
    }

    /** Returns whether write operations through this plugin's scripting APIs require confirmation.
     * @return {@code true} if write confirmation is required, {@code false} otherwise
     */
    default boolean requiresWriteConfirmation() {
        return true;
    }
}