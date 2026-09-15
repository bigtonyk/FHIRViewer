package com.example.fhirviewer.service;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * Validates resources with HAPI FHIR.
 *
 * <p>The validator is created lazily and reused, because building the validation
 * support (profile and terminology data) is expensive. The validation support chain
 * combines the built-in R4 profiles that ship with HAPI with in-memory terminology
 * and snapshot generation, so validation works without a FHIR server.</p>
 */
public class ValidationService {

    private final FhirContext context;
    private volatile FhirValidator validator;

    public ValidationService(FhirContext context) {
        this.context = context;
    }

    /**
     * Validates a resource.
     *
     * <p>Validation failures are converted into a {@link ValidationReport} containing an
     * error message rather than thrown, so the UI can display them.</p>
     */
    public ValidationReport validate(IBaseResource resource) {
        if (resource == null) {
            return ValidationReport.successful("");
        }
        List<ValidationIssue> issues = new ArrayList<>();
        try {
            ValidationResult result = validator().validateWithResult(resource);
            for (SingleValidationMessage message : result.getMessages()) {
                issues.add(new ValidationIssue(
                        toSeverity(message.getSeverity()),
                        message.getMessage(),
                        message.getLocationString(),
                        message.getLocationLine(),
                        message.getLocationCol()));
            }
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            issues.add(new ValidationIssue(
                    ValidationIssue.Severity.ERROR,
                    "Validation could not be completed: " + detail,
                    "",
                    null,
                    null));
        }
        return new ValidationReport(labelOf(resource), issues);
    }

    private FhirValidator validator() {
        FhirValidator existing = validator;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (validator == null) {
                validator = createValidator();
            }
            return validator;
        }
    }

    private FhirValidator createValidator() {
        IValidationSupport validationSupport = new ValidationSupportChain(
                new DefaultProfileValidationSupport(context),
                new InMemoryTerminologyServerValidationSupport(context),
                new SnapshotGeneratingValidationSupport(context));

        FhirValidator fhirValidator = new FhirValidator(context);
        fhirValidator.registerValidatorModule(new FhirInstanceValidator(validationSupport));
        return fhirValidator;
    }

    private static ValidationIssue.Severity toSeverity(ResultSeverityEnum severity) {
        if (severity == null) {
            return ValidationIssue.Severity.INFORMATION;
        }
        return switch (severity) {
            case FATAL -> ValidationIssue.Severity.FATAL;
            case ERROR -> ValidationIssue.Severity.ERROR;
            case WARNING -> ValidationIssue.Severity.WARNING;
            case INFORMATION -> ValidationIssue.Severity.INFORMATION;
        };
    }

    private static String labelOf(IBaseResource resource) {
        String type = resource.fhirType();
        IIdType id = resource.getIdElement();
        if (id == null || !id.hasIdPart()) {
            return type;
        }
        return type + "/" + id.getIdPart();
    }
}