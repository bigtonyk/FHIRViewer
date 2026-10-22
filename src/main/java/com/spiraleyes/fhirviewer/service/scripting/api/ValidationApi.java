package com.spiraleyes.fhirviewer.service.scripting.api;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.model.ValidationReport;
import com.spiraleyes.fhirviewer.service.FhirService;

/**
 * Validation of FHIR resources, returning structured results the script can
 * inspect instead of throwing.
 *
 * <p>Validation never throws: a broken resource becomes an issue on the report,
 * and a missing profile becomes an explanatory message on the report.
 */
public class ValidationApi {

    private final FhirService fhirService;

    public ValidationApi(FhirService fhirService) {
        this.fhirService = fhirService == null ? new FhirService() : fhirService;
    }

    /** Validates a resource against its base FHIR definition and any profiles it claims. */
    public ValidationReport validate(IBaseResource resource) {
        return fhirService.validate(resource);
    }

    /**
     * Validates a resource against the given profile canonical URL. A blank
     * profile means "use the resource's own {@code meta.profile}".
     */
    public ValidationReport validate(IBaseResource resource, String profileCanonical) {
        return fhirService.validate(resource, profileCanonical);
    }
}
