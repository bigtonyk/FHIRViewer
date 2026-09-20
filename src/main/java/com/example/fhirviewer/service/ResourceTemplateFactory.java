package com.example.fhirviewer.service;

import java.util.Objects;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.fhir.FhirContextFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;

/**
 * Creates minimal FHIR resource instances suitable for editing.
 *
 * <p>Templates are built from the model definition so the application stays
 * version neutral; all concrete types are asked for from the runtime resource
 * definition.</p>
 */
public final class ResourceTemplateFactory {

    private final FhirContext context;

    public ResourceTemplateFactory(FhirContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * Creates a minimal, editable resource of the given type.
     *
     * @param resourceType   the FHIR type name, for example {@code Patient}
     * @return a new resource instance
     * @throws IllegalArgumentException when the type is not recognised or has no
     *                                  concrete implementation
     */
    public IBaseResource createEmpty(String resourceType) {
        Objects.requireNonNull(resourceType, "resourceType");
        RuntimeResourceDefinition def;
        try {
            def = context.getResourceDefinition(resourceType);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("There is no FHIR resource type named " + resourceType + ".", e);
        }
        IBaseResource instance = def.newInstance();
        if (instance == null) {
            throw new IllegalArgumentException("no implementation for " + resourceType);
        }
        return instance;
    }

    /**
     * The names of every resource type of the configured FHIR version, sorted, so the
     * "New" dialog offers the list the model actually supports.
     */
    public List<String> resourceTypeNames() {
        return context.getResourceTypes().stream().sorted().toList();
    }

    /** The context this factory was built on. */
    public FhirContext context() {
        return context;
    }

    /** Shortcut constructor for the default R4 context. */
    public static ResourceTemplateFactory r4() {
        return new ResourceTemplateFactory(FhirContextFactory.r4());
    }
}
