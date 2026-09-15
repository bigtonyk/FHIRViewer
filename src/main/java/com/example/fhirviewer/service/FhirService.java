package com.example.fhirviewer.service;

import java.nio.file.Path;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.fhir.FhirContextFactory;
import com.example.fhirviewer.fhir.FhirModelAdapter;
import com.example.fhirviewer.fhir.ResourceParser;
import com.example.fhirviewer.fhir.ResourceSerializer;
import com.example.fhirviewer.fhir.ResourceTreeBuilder;
import com.example.fhirviewer.model.BundleEntryInfo;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.model.ValidationReport;
import com.example.fhirviewer.pretty.PrettyDocument;
import com.example.fhirviewer.pretty.PrettyModelBuilder;

import ca.uhn.fhir.context.FhirContext;

/**
 * The application/service layer: coordinates loading, tree building, serialization
 * and validation.
 *
 * <p>The UI talks to this class and never directly to HAPI FHIR, keeping FHIR
 * processing out of the JavaFX event handlers.</p>
 */
public class FhirService {

    /** The sample resources that ship with the application. */
    public static final String SAMPLE_PATIENT = "/samples/patient-example.json";
    public static final String SAMPLE_BUNDLE = "/samples/bundle-example.json";

    private final FhirModelAdapter modelAdapter;
    private final ResourceSerializer serializer;
    private final ResourceLoader loader;
    private final ResourceTreeBuilder treeBuilder;
    private final PrettyModelBuilder prettyBuilder;
    private final ValidationService validationService;

    /** Creates a service for the default (R4) FHIR version. */
    public FhirService() {
        this(FhirContextFactory.r4(), FhirContextFactory.r4ModelAdapter());
    }

    /**
     * Creates a service for a specific context and model adapter. Kept public so a
     * future FHIR version can be wired in without changing this class.
     */
    public FhirService(FhirContext context, FhirModelAdapter modelAdapter) {
        this.modelAdapter = modelAdapter;
        this.serializer = new ResourceSerializer(context);
        this.loader = new ResourceLoader(new ResourceParser(context));
        this.treeBuilder = new ResourceTreeBuilder(modelAdapter);
        this.prettyBuilder = new PrettyModelBuilder(modelAdapter);
        this.validationService = new ValidationService(context);
    }

    /** Opens a resource file from disk. */
    public LoadedResource openFile(Path path) {
        return loader.loadFile(path);
    }

    /** Opens a sample resource bundled with the application. */
    public LoadedResource openSample(String classpathResource) {
        return loader.loadClasspathResource(classpathResource);
    }

    /** Opens a resource held in memory, for example pasted text. */
    public LoadedResource openText(String text, String sourceName) {
        return loader.loadText(text, sourceName);
    }

    /**
     * Builds the resource tree for a loaded resource.
     *
     * @param includeUnpopulated when {@code true} elements that are defined but empty
     *                           are included, which is useful when inspecting the
     *                           structure of a resource
     */
    public ResourceNode buildTree(LoadedResource loaded, boolean includeUnpopulated) {
        IBaseResource resource = loaded.getResource();
        return treeBuilder.build(resource, resource.fhirType(), loaded.getResourceId(), includeUnpopulated);
    }

    /** Builds the resource tree for one Bundle entry. */
    public ResourceNode buildTree(BundleEntryInfo entry, boolean includeUnpopulated) {
        IBaseResource resource = entry.resource();
        if (resource == null) {
            throw new ResourceLoadException("Bundle entry " + entry.index() + " does not contain a resource.", null);
        }
        return treeBuilder.build(resource, entry.displayName(), null, includeUnpopulated);
    }

    /**
     * Builds the human friendly presentation model of a loaded resource for the
     * Pretty View.
     */
    public PrettyDocument buildPrettyView(LoadedResource loaded) {
        return prettyBuilder.build(loaded.getResource());
    }

    /** Builds the human friendly presentation model of one Bundle entry for the Pretty View. */
    public PrettyDocument buildPrettyView(BundleEntryInfo entry) {
        IBaseResource resource = entry.resource();
        if (resource == null) {
            throw new ResourceLoadException("Bundle entry " + entry.index() + " does not contain a resource.", null);
        }
        return prettyBuilder.build(resource);
    }

    /** Renders a resource as pretty printed JSON. */
    public String toJson(IBaseResource resource) {
        return serializer.toJson(resource);
    }

    /** Renders a resource as pretty printed XML. */
    public String toXml(IBaseResource resource) {
        return serializer.toXml(resource);
    }

    /** Renders a resource in the requested format. */
    public String serialize(IBaseResource resource, ResourceFormat format) {
        return serializer.serialize(resource, format);
    }

    /** Validates a resource and returns the issues found (never throws). */
    public ValidationReport validate(IBaseResource resource) {
        return validationService.validate(resource);
    }

    /** Returns the entries of a Bundle, or an empty list for other resources. */
    public List<BundleEntryInfo> bundleEntries(IBaseResource resource) {
        return modelAdapter.entriesOf(resource);
    }

    /** True when the resource is a Bundle. */
    public boolean isBundle(IBaseResource resource) {
        return resource != null && "Bundle".equals(resource.fhirType());
    }

    /** The FHIR version this service is configured for, for example <code>R4</code>. */
    public String fhirVersion() {
        return modelAdapter.fhirVersionName();
    }
}