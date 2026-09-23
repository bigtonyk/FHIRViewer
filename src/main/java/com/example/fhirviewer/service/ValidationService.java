package com.example.fhirviewer.service;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
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
 * Validates resources with HAPI FHIR, including support for loaded IG packages.
 */
public class ValidationService {

    private final FhirContext context;
    private volatile FhirValidator validator;
    /** Package count the cached validator was built with; -1 before the first build. */
    private volatile int validatorPackageCount = -1;
    private final IgPackageManager packageManager;

    public ValidationService(FhirContext context) {
        this.context = context;
        this.packageManager = new IgPackageManager(context);
    }

    public IgPackageManager getPackageManager() {
        return packageManager;
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
        
        // Check profile resolution for meta.profile references
        checkProfileResolution(resource, issues);
        
        try {
            ValidationResult result = validator().validateWithResult(resource);
            for (SingleValidationMessage message : result.getMessages()) {
                issues.add(new ValidationIssue(
                        toSeverity(message.getSeverity()),
                        message.getMessage(),
                        message.getLocationString(),
                        message.getLocationLine(),
                        message.getLocationCol(),
                        false));
            }
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            issues.add(new ValidationIssue(
                    ValidationIssue.Severity.ERROR,
                    "Validation could not be completed: " + detail,
                    "", null, null, false));
        }
        return new ValidationReport(labelOf(resource), issues);
    }

    private void checkProfileResolution(IBaseResource resource, List<ValidationIssue> issues) {
        // Profile resolution checking is handled by the validator when IG packages are loaded
        // This method is a placeholder for future enhancement
    }

    private FhirValidator validator() {
        int packageCount = packageManager.getLoadedPackageCount();
        FhirValidator existing = validator;
        if (existing != null && validatorPackageCount == packageCount) {
            return existing;
        }
        synchronized (this) {
            if (validator == null || validatorPackageCount != packageCount) {
                validator = createValidator();
                validatorPackageCount = packageCount;
            }
            return validator;
        }
    }

    private FhirValidator createValidator() {
        List<IValidationSupport> supports = new ArrayList<>();
        supports.add(new DefaultProfileValidationSupport(context));
        supports.add(new InMemoryTerminologyServerValidationSupport(context));
        supports.add(new SnapshotGeneratingValidationSupport(context));
        
        NpmPackageValidationSupport npmSupport = packageManager.getNpmPackageValidationSupport();
        if (npmSupport != null && packageManager.hasLoadedPackages()) {
            supports.add(npmSupport);
        }
        
        IValidationSupport validationSupport = new ValidationSupportChain(
                supports.toArray(new IValidationSupport[0]));
        
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