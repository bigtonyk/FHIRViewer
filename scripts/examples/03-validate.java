// Validate a resource
var p = fhir.read("Patient", "example-id");
if (p != null) {
    var report = validation.validate(p);
    log.info("Validation done");
    var report2 = validation.validate(p, "http://hl7.org/fhir/StructureDefinition/Patient");
    viewer.showText("validated");
}
