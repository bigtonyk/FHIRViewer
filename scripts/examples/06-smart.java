// SMART context example
if (smart.isSessionActive()) {
    log.info("scopes: " + smart.grantedScopes());
    log.info("patient: " + smart.launchPatientId());
    log.info("encounter: " + smart.launchEncounterId());
    var r = smart.read("Patient", smart.launchPatientId());
    if (r != null) viewer.showResource(r);
} else {
    log.warn("No active SMART session");
}
