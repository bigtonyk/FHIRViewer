package com.example.fhirviewer.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.ResourceFormat;

import ca.uhn.fhir.context.FhirContext;

/**
 * Serializes HAPI FHIR resources back to JSON or XML text for display and export.
 */
public class ResourceSerializer {

    private final FhirContext context;

    public ResourceSerializer(FhirContext context) {
        this.context = context;
    }

    /** Renders the resource as pretty printed FHIR JSON. */
    public String toJson(IBaseResource resource) {
        return context.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    /** Renders the resource as pretty printed FHIR XML. */
    public String toXml(IBaseResource resource) {
        return context.newXmlParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    /** Renders the resource in the requested format. */
    public String serialize(IBaseResource resource, ResourceFormat format) {
        return switch (format) {
            case JSON -> toJson(resource);
            case XML -> toXml(resource);
        };
    }
}