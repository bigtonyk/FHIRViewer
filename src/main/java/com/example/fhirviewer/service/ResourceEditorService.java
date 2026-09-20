package com.example.fhirviewer.service;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.example.fhirviewer.fhir.FhirContextFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.util.FhirTerser;

/**
 * Edits a live FHIR resource and the elements inside it.
 *
 * <p>The UI coordinates editing but never touches HAPI FHIR directly; every mutation
 * goes through this class, so the service layer is the only place that owns the terser
 * and the rules for resolving element paths.</p>
 *
 * <p>Paths are the ones the resource tree renders: absolute from the resource type and
 * with an index for every entry of a repeating element, for example
 * {@code Patient.name[0].family}, {@code Patient.name[0].given[1]} or
 * {@code Patient.someElement} (choice elements use the concrete name the tree shows,
 * such as {@code deceasedBoolean}). A node selected in the tree can therefore be edited
 * without any name translation in the UI.</p>
 *
 * <p>The FHIR terser only understands plain dot separated paths and cannot address a
 * single entry of a repeating element, so this class walks such a path itself, taking
 * the indexed entry the path names, and then applies the change to that element.</p>
 */
public final class ResourceEditorService {

    private final FhirContext context;
    private final FhirTerser terser;

    /** Creates an editor for the default R4 FHIR version. */
    public ResourceEditorService() {
        this(FhirContextFactory.r4());
    }

    /** Creates an editor for a specific FHIR context. */
    public ResourceEditorService(FhirContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.terser = context.newTerser();
    }

    /** The FHIR context this editor works with. */
    public FhirContext context() {
        return context;
    }

    /**
     * Sets a primitive element to a text value, creating the element when it is not
     * present yet. The text is converted to the element's datatype by HAPI FHIR, so a
     * date is stored as a date and an invalid value is reported instead of being written.
     *
     * @param resource the resource to edit
     * @param fhirPath the absolute element path, for example {@code Patient.name[0].family}
     * @param text     the new value
     * @return the value that was set
     * @throws IllegalArgumentException when the path is blank, unknown or does not name a
     *                                  primitive element, or when the text cannot be
     *                                  converted to the element's datatype
     */
    public String setPrimitive(IBaseResource resource, String fhirPath, String text) {
        Objects.requireNonNull(resource, "resource");
        PathParts parts = split(fhirPath);
        if (text == null) {
            throw new IllegalArgumentException("A null value is not accepted as the value of " + parts.path() + ".");
        }
        try {
            IBase owner = resolve(resource, parts.parentPath());
            List<IBase> values = terser.getValues(owner, parts.name());
            if (parts.index() > 0) {
                // HAPI always addresses the first entry of a repeating element, so any
                // other entry is updated on the element itself.
                if (parts.index() >= values.size()) {
                    throw new IllegalArgumentException("There is no element at " + parts.path() + ".");
                }
                IBase value = values.get(parts.index());
                if (!(value instanceof IPrimitiveType<?> primitive)) {
                    throw new IllegalArgumentException("The element " + parts.path() + " is not a primitive value.");
                }
                primitive.setValueAsString(text);
            } else {
                terser.setElement(owner, parts.name(), text);
            }
            return text;
        } catch (RuntimeException e) {
            throw editFailure(parts.path(), e);
        }
    }

    /**
     * Adds a new, empty child element: a new entry for a repeating element such as
     * {@code Patient.name}, or the element itself when it is not present yet. The new
     * element is returned so the caller can select it and fill in its values.
     *
     * @throws IllegalArgumentException when the path is blank or unknown, when it names a
     *                                  single existing entry instead of a repeating
     *                                  element, or when the element may not repeat and
     *                                  already has a value
     */
    public IBase addElement(IBaseResource resource, String fhirPath) {
        Objects.requireNonNull(resource, "resource");
        PathParts parts = split(fhirPath);
        if (parts.index() >= 0) {
            throw new IllegalArgumentException("A new entry cannot be added at " + parts.path()
                    + " because that names a single entry; add to the repeating element instead.");
        }
        try {
            IBase added = terser.addElement(resolve(resource, parts.parentPath()), parts.name());
            if (added == null) {
                throw new IllegalArgumentException("No element could be added at " + parts.path() + ".");
            }
            return added;
        } catch (RuntimeException e) {
            throw editFailure(parts.path(), e);
        }
    }

    /**
     * Sets the target of a FHIR reference element, creating the reference when it is not
     * present yet.
     *
     * @param target the new target, for example {@code Practitioner/123} or an absolute URL
     * @return the target that was set
     * @throws IllegalArgumentException when the path is blank or unknown, when the element
     *                                  is not a reference, or when the target is blank
     */
    public String setReference(IBaseResource resource, String fhirPath, String target) {
        Objects.requireNonNull(resource, "resource");
        PathParts parts = split(fhirPath);
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("A blank reference target is not accepted for " + parts.path() + ".");
        }
        try {
            IBase owner = resolve(resource, parts.parentPath());
            List<IBase> values = terser.getValues(owner, parts.name());
            IBase value;
            if (parts.index() >= 0) {
                if (parts.index() >= values.size()) {
                    throw new IllegalArgumentException("There is no element at " + parts.path() + ".");
                }
                value = values.get(parts.index());
            } else if (values.isEmpty()) {
                value = terser.addElement(owner, parts.name());
            } else {
                value = values.get(0);
            }
            if (!(value instanceof IBaseReference reference)) {
                throw new IllegalArgumentException("The element " + parts.path() + " is not a FHIR Reference.");
            }
            reference.setReference(target);
            return target;
        } catch (RuntimeException e) {
            throw editFailure(parts.path(), e);
        }
    }

    /** The number of values present at a path, or {@code 0} when the element is absent. */
    public int valueCount(IBaseResource resource, String fhirPath) {
        Objects.requireNonNull(resource, "resource");
        PathParts parts = split(fhirPath);
        try {
            return terser.getValues(resolve(resource, parts.parentPath()), parts.name()).size();
        } catch (RuntimeException e) {
            throw editFailure(parts.path(), e);
        }
    }

    /** Copies a resource, so the UI can keep a snapshot before editing it. */
    public IBaseResource cloneResource(IBaseResource original) {
        if (original == null) {
            throw new IllegalArgumentException("A null resource cannot be copied.");
        }
        try {
            return terser.clone(original);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Could not copy the resource: " + messageOf(e), e);
        }
    }

    /**
     * Resolves the element that a tree path names.
     *
     * <p>The path is walked one segment at a time, because the FHIR terser cannot address
     * a single entry of a repeating element: the index read from the path is used to pick
     * the value out of the child element the segment names.</p>
     *
     * @param path an absolute path with indexes, or an empty string for the resource itself
     */
    private IBase resolve(IBaseResource resource, String path) {
        if (path == null || path.isEmpty()) {
            return resource;
        }
        String[] segments = path.split("\\.");
        IBase current = resource;
        // The first segment is the resource type, which is not an element name.
        for (int i = segments[0].equals(resource.fhirType()) ? 1 : 0; i < segments.length; i++) {
            PathParts segment = split(segments[i]);
            List<IBase> values = terser.getValues(current, segment.name());
            int index = segment.index() < 0 ? 0 : segment.index();
            if (index >= values.size()) {
                throw new IllegalArgumentException("The element " + segment.name() + " of " + path + " has "
                        + values.size() + (values.size() == 1 ? " value" : " values")
                        + ", so " + segment.name() + "[" + index + "] does not exist.");
            }
            current = values.get(index);
        }
        return current;
    }

    /** Splits a tree path into the part that names the owner and the child to change. */
    private static PathParts split(String fhirPath) {
        if (fhirPath == null || fhirPath.isBlank()) {
            throw new IllegalArgumentException("A blank element path is not accepted.");
        }
        String path = fhirPath.trim();
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return segment(path, path, "");
        }
        return segment(path, path.substring(dot + 1), path.substring(0, dot));
    }

    /** Splits one path segment such as <code>name[0]</code> into its name and index. */
    private static PathParts segment(String path, String segment, String parentPath) {
        int open = segment.indexOf('[');
        if (open < 0 || !segment.endsWith("]")) {
            return new PathParts(path, parentPath, segment, -1);
        }
        int index;
        try {
            index = Integer.parseInt(segment.substring(open + 1, segment.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("There is no element index in " + path + ".");
        }
        return new PathParts(path, parentPath, segment.substring(0, open), index);
    }

    /** Wraps a HAPI failure in the exception type the UI reports to the user. */
    private static IllegalArgumentException editFailure(String path, RuntimeException failure) {
        if (failure instanceof IllegalArgumentException illegal) {
            return illegal;
        }
        return new IllegalArgumentException("Could not edit " + path + ": " + messageOf(failure), failure);
    }

    private static String messageOf(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /**
     * A tree path split into the element that owns a change and the child to change.
     *
     * @param path       the original path, used in messages
     * @param parentPath the path of the owning element, or an empty string for the resource
     * @param name       the child element name, without an index
     * @param index      the entry index, or {@code -1} when the path names no single entry
     */
    private record PathParts(String path, String parentPath, String name, int index) { }
}
