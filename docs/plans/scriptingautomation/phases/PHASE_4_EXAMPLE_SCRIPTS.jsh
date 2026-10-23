// Phase 4: Extensibility and Ecosystem - Example Scripts
// These demonstrations show how plugins can advertise capabilities,
// register typed API objects/operations, and support versioned examples.

// Example 1: Using plugin-provided API objects
// This script assumes a plugin is installed that provides additional API bindings
optionalPlugin.ifPresent(plugin -> {
    // Plugin-specific operations are available through the plugin
    // Example: plugin-specific search or validation
});

// Example 2: Versioned FHIR operations
// Script works with R4/FHIR version, checking compatibility
var version = smart.getFhirVersion(); // Returns current server FHIR version
if (version.contains("R4")) {
    // R4-specific operations
    var patient = fhir.read("Patient", "example-patient-id");
    if (patient != null) {
        viewer.showResource(patient);
        log.info("Patient loaded via R4 API");
    }
}

// Example 3: Batch processing with plugin enhancements
// Demonstrates batch workflows supported by the foundation
var batch = new BatchProcessor();
batch.setMaxErrors(5);
batch.onError((error, context) -> {
    log.warning("Batch error: " + error.getMessage());
    return BatchProcessor.ErrorAction.SKIP;
});

// Example 4: Import workflow with warnings
// Imported scripts should be reviewed before execution
// importScript("scripts/example-workflow.jsh")
// Note: Imported scripts execute as trusted code - review before running

// Example 5: Export with metadata preservation
// Export script with execution warnings preserved
// exportScript("scripts/my-workflow.jsh", {
//     description: "Workflow demonstrating plugin-enhanced FHIR operations",
//     version: "1.0.0",
//     warnings: ["Script executes trusted code", "Requires plugin XYZ for full functionality"]
// });

// Example 6: API compatibility check
// Verify the script API is available in the current environment
if (typeof fhir === "undefined") {
    log.severe("Core FHIR API not available - cannot run script");
}

// Example 7: Write confirmation for mutating operations
// Central write confirmation policy applies
if (confirm("This operation will modify server state. Proceed?")) {
    // Perform mutating operation
    fhir.create("Patient", patientResource);
}

// Example 8: Plugin discovery
// List available plugin scripting capabilities
var pluginDescriptions = [];
// Pseudo-code for discovering plugin capabilities
// var plugins = server.getPlugins();
// for (var p of plugins) {
//     if (p.hasScriptingApi()) {
//         pluginDescriptions.push(p.scriptingDescription());
//     }
// }
// log.info("Available plugin scripting capabilities: " + pluginDescriptions.join(", "));