// Server info and capabilities
var s = server.activeServer();
if (s != null) {
    log.info(s.toString());
    var caps = server.capabilities();
    log.info("FHIR version: " + server.fhirVersion());
    boolean ok = server.supports("Patient", ServerInteraction.READ);
    log.info("Patient READ supported: " + ok);
    for (var srv : server.servers()) {
        log.info(srv.name() + " " + srv.baseUrl());
    }
}
