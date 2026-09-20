package com.example.fhirviewer.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.fhir.FhirParseException;
import com.example.fhirviewer.fhir.ResourceParser;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.util.FileSupport;

/**
 * Loads FHIR resources from files, from the application classpath and from raw text.
 *
 * <p>The format is detected from the content first and from the file extension second.
 * When the detected format cannot be parsed the other format is attempted, so a
 * mislabelled file still opens.</p>
 */
public class ResourceLoader {

    private final ResourceParser parser;

    public ResourceLoader(ResourceParser parser) {
        this.parser = parser;
    }

    /** Loads a resource from a file on disk. */
    public LoadedResource loadFile(Path path) {
        String text;
        try {
            text = FileSupport.readText(path);
        } catch (IOException e) {
            throw new ResourceLoadException("Could not read " + path + ": " + e.getMessage(), e);
        }
        return loadText(text, FileSupport.fileName(path), path);
    }

    /** Loads a resource that is bundled with the application. */
    public LoadedResource loadClasspathResource(String resourcePath) {
        String text;
        try {
            text = FileSupport.readClasspathText(resourcePath);
        } catch (IOException e) {
            throw new ResourceLoadException("Could not read sample " + resourcePath + ": " + e.getMessage(), e);
        }
        return loadText(text, resourcePath);
    }

    /**
     * Loads a resource from text.
     *
     * @param text       the FHIR document
     * @param sourceName a name describing the origin, used for display and errors
     */
    public LoadedResource loadText(String text, String sourceName) {
        return loadText(text, sourceName, null);
    }

    /**
     * Loads a resource from text and remembers the file it came from, so that saving
     * can write the resource back to the same place.
     */
    private LoadedResource loadText(String text, String sourceName, Path sourcePath) {
        String content = FileSupport.stripByteOrderMark(text);
        Optional<ResourceFormat> detected = ResourceFormat.detect(content, sourceName);
        ResourceFormat format = detected.orElse(ResourceFormat.JSON);

        IBaseResource resource;
        try {
            resource = parser.parse(content, format, sourceName);
        } catch (FhirParseException firstFailure) {
            ResourceFormat alternative = format.other();
            try {
                resource = parser.parse(content, alternative, sourceName);
                format = alternative;
            } catch (FhirParseException ignored) {
                throw new ResourceLoadException(firstFailure.getMessage(), firstFailure);
            }
        }
        return new LoadedResource(resource, format, sourceName, content, sourcePath);
    }
}