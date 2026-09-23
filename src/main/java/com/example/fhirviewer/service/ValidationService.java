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
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * Validates resources with HAPI FHIR, including support for loaded IG packages.
 */
public class ValidationService {

    private final FhirContext context;
    private volatile FhirValidator validator;
    /** Validation support chain the cached validator was built with; null before the first build. */
    private volatile IValidationSupport validationSupport;
    /** Package revision the cached validator was built with; -1 before the first build. */
    private volatile long validatorRevision = -1;
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
        
        // Profile and terminology resolution checks run before the validator so
        // missing artifacts are distinguished from real validation failures.
        
        try {
            FhirValidator current = validator();
            ValidationIssueAnalyzer analyzer = new ValidationIssueAnalyzer(validationSupport);
            analyzer.checkProfileBindings(resource, issues);

            ValidationResult result = current.validateWithResult(resource);
            for (SingleValidationMessage message : result.getMessages()) {
                issues.add(analyzer.classify(message));
            }
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            issues.add(new ValidationIssue(
                    ValidationIssue.Severity.ERROR,
                    "Validation could not be completed: " + detail,
                    "", null, null));
        }
        return new ValidationReport(labelOf(resource), issues);
    }



    private FhirValidator validator() {
        long revision = packageManager.getRevision();
        FhirValidator existing = validator;
        if (existing != null && validatorRevision == revision) {
            return existing;
        }
        synchronized (this) {
            if (validator == null || validatorRevision != revision) {
                validator = createValidator();
                validatorRevision = revision;
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
        
        IValidationSupport chain = new ValidationSupportChain(
                supports.toArray(new IValidationSupport[0]));
        validationSupport = chain;
        
        FhirValidator fhirValidator = new FhirValidator(context);
        fhirValidator.registerValidatorModule(new FhirInstanceValidator(chain));
        return fhirValidator;
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