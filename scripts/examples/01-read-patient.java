// Basic example: read a Patient and display it
var patient = fhir.read("Patient", "example-id");
if (patient != null) {
    viewer.showResource(patient);
    log.info("Patient loaded");
} else {
    log.warn("Patient not found");
}
