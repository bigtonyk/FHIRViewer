package com.example.fhirviewer.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;

/**
 * Provides the shared, cached HAPI FHIR context used by the application.
 *
 * <p>The context is expensive to create, so a single instance is reused. The FHIR
 * version is chosen here and nowhere else, keeping version specific decisions in
 * one place (see {@link FhirModelAdapter} for the corresponding model adapter).</p>
 */
public final class FhirContextFactory {

    /** The FHIR version this build of the viewer targets. */
    public static final String FHIR_VERSION = "R4";

    private static final FhirContext R4_CONTEXT = FhirContext.forR4();

    private FhirContextFactory() {
        // static factory
    }

    /** The shared R4 context. */
    public static FhirContext r4() {
        return R4_CONTEXT;
    }

    /**
     * The FHIRPath engine that matches {@link #r4()}. Built on the HAPI FHIRPath
     * wrapper so the service layer only sees the version neutral
     * <code>IFhirPath</code> interface.
     */
    public static IFhirPath r4FhirPath() {
        return new FhirPathR4(R4_CONTEXT);
    }

    /** The model adapter that matches {@link #r4()}. */
    public static FhirModelAdapter r4ModelAdapter() {
        return new R4ModelAdapter();
    }
}