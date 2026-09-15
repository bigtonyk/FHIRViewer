package com.example.fhirviewer.fhir;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.BundleEntryInfo;

/**
 * The only place in the application where FHIR version specific model classes are used.
 *
 * <p>The application is written against this interface plus the version neutral
 * <code>org.hl7.fhir.instance.model.api.*</code> interfaces, so supporting another
 * FHIR version means adding one implementation of this interface rather than
 * touching the tree builder, the services or the UI.</p>
 */
public interface FhirModelAdapter {

    /** The name of the FHIR version this adapter works with, for example <code>R4</code>. */
    String fhirVersionName();

    /**
     * Returns the single level, populated-or-defined child elements of a model object.
     *
     * @param element any model object (resource, datatype, primitive or extension)
     * @return the element metadata in declaration order; never {@code null}
     */
    List<ElementProperty> propertiesOf(IBase element);

    /**
     * Returns the entries of a Bundle resource.
     *
     * @param resource the resource to inspect
     * @return the entries in order, or an empty list when the resource is not a Bundle
     */
    List<BundleEntryInfo> entriesOf(IBaseResource resource);
}