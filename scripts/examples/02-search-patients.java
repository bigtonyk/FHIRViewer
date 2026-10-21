// Search patients and show results
var results = fhir.search("Patient");
viewer.showSearchResults(results);
log.info("Found " + results.size() + " patients");
if (results.hasNextPage()) {
    var next = fhir.nextPage(results.nextPageToken());
    viewer.showSearchResults(next);
}
