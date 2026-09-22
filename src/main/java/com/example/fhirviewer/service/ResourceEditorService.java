package com.example.fhirviewer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.example.fhirviewer.fhir.ElementProperty;
import com.example.fhirviewer.fhir.FhirContextFactory;
import com.example.fhirviewer.fhir.FhirModelAdapter;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeDeclaredChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
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
    private final FhirModelAdapter modelAdapter;
    private final FhirTerser terser;

    /** Creates an editor for the default R4 FHIR version. */
    public ResourceEditorService() {
        this(FhirContextFactory.r4(), FhirContextFactory.r4ModelAdapter());
    }

    /** Creates an editor for a specific FHIR context. */
    public ResourceEditorService(FhirContext context) {
        this(context, FhirContextFactory.r4ModelAdapter());
    }

    /**
     * Creates an editor for a specific FHIR context and model adapter. The adapter
     * carries out the model level changes, such as deleting values, that the terser
     * alone cannot express.
     */
    public ResourceEditorService(FhirContext context, FhirModelAdapter modelAdapter) {
        this.context = Objects.requireNonNull(context, "context");
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
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
            IBase owner = resolve(resource, parts.parentPath());
            BaseRuntimeElementCompositeDefinition<?> ownerDefinition = compositeDefinitionOf(owner);
            if (ownerDefinition != null) {
                BaseRuntimeChildDefinition childDefinition = ownerDefinition.getChildByName(parts.name());
                if (childDefinition != null && childDefinition.getMax() == 1
                        && !terser.getValues(owner, parts.name()).isEmpty()) {
                    throw new IllegalArgumentException("The element " + parts.path() + " already has a value and"
                            + " may not repeat; edit the value instead of adding another one.");
                }
            }
            IBase added = terser.addElement(owner, parts.name());
            if (added == null) {
                throw new IllegalArgumentException("No element could be added at " + parts.path() + ".");
            }
            return added;
        } catch (RuntimeException e) {
            throw editFailure(parts.path(), e);
        }
    }

    /**
     * Adds a new, empty child element under the parent element that a tree path
     * names, for example a {@code family} element under {@code Patient.name[0]} or a
     * new {@code name} entry under {@code Patient}. The new element is returned so
     * the caller can select it and fill in its values.
     *
     * @param parentPath the absolute path of the element that receives the child
     * @param childName  the child element name, exactly as the tree renders it; for
     *                   choice elements the concrete name, for example
     *                   {@code deceasedBoolean}
     * @return the added element
     * @throws IllegalArgumentException when the parent path is blank or unknown, the
     *                                  child name is blank or unknown, or the child
     *                                  already carries a value and may not repeat
     */
    public IBase addNode(IBaseResource resource, String parentPath, String childName) {
        Objects.requireNonNull(resource, "resource");
        if (childName == null || childName.isBlank()) {
            throw new IllegalArgumentException(
                    "A blank child element name is not accepted under " + parentPath + ".");
        }
        String parent = parentPath == null ? "" : parentPath.trim();
        if (parent.isEmpty()) {
            throw new IllegalArgumentException(
                    "A blank parent path is not accepted for a new " + childName.trim() + ".");
        }
        return addElement(resource, parent + "." + childName.trim());
    }

    /**
     * Deletes the element that a tree path names: one entry of a repeating element
     * (for example {@code Patient.name[1]}), every value of an element (for example
     * {@code Patient.name[0].given}) or a single non repeating element (for example
     * {@code Patient.name[0].family}).
     *
     * @throws IllegalArgumentException when the path is blank or unknown, when it
     *                                  names the resource itself, or when the element
     *                                  has nothing to delete
     */
    public void deleteNode(IBaseResource resource, String fhirPath) {
        Objects.requireNonNull(resource, "resource");
        PathParts parts = split(fhirPath);
        if (parts.parentPath().isBlank()) {
            throw new IllegalArgumentException(
                    "The resource itself (" + parts.path() + ") cannot be deleted; close it instead.");
        }
        try {
            modelAdapter.removeValues(resolve(resource, parts.parentPath()), parts.name(), parts.index());
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

    /**
     * The child elements that the FHIR resource definition allows under the element
     * a tree path names, each with its datatype, cardinality and specification
     * documentation. Choice elements are listed under their concrete names (for
     * example {@code deceasedBoolean}), and elements that already carry their single
     * value are left out, because nothing can be added to them.
     *
     * @param parentPath the absolute path of the element to inspect
     * @return the allowed child elements in specification order; never {@code null}
     * @throws IllegalArgumentException when the path is blank, unknown, or names an
     *                                  element that cannot hold child elements
     */
        public List<ElementProperty> childElements(IBaseResource resource, String parentPath) {
        Objects.requireNonNull(resource, "resource");
        if (parentPath == null || parentPath.isBlank()) {
            throw new IllegalArgumentException("A blank parent path has no child elements.");
        }
        try {
            IBase owner = resolve(resource, parentPath);
            BaseRuntimeElementCompositeDefinition<?> definition = compositeDefinitionOf(owner);
            if (definition == null) {
                throw new IllegalArgumentException("The element " + parentPath + " has no child elements.");
            }
            List<ElementProperty> children = new ArrayList<>();
            for (BaseRuntimeChildDefinition child : definition.getChildren()) {
                for (String name : namesOf(child)) {
                    BaseRuntimeElementDefinition<?> typeDefinition;
                    try {
                        typeDefinition = child.getChildByName(name);
                    } catch (RuntimeException | AssertionError e) {
                        // HAPI's RuntimeChildExtension.getChildByName translates the name
                        // "extension" to the internal "extensionExtension" and throws when
                        // that choice is not resolvable. Such internal names are not
                        // editable from the UI, so skip them.
                        continue;
                    }
                    if (typeDefinition == null) {
                        continue;
                    }
                    if (child.getMax() == 1 && !terser.getValues(owner, name).isEmpty()) {
                        // The element already carries its single value, so nothing can be added.
                        continue;
                    }
                    children.add(new ElementProperty(
                            name,
                            typeDefinition.getName(),
                            definitionTextOf(child),
                            child.getMin(),
                            child.getMax(),
                            List.of()));
                }
            }
            return children;
        } catch (RuntimeException e) {
            throw editFailure(parentPath, e);
        }
    }

    /**
     * The element a tree path names, so callers can inspect what an edit would
     * change without duplicating the path walking rules.
     *
     * @throws IllegalArgumentException when the path is blank or does not name an
     *                                  existing element
     */
    public IBase resolveElement(IBaseResource resource, String fhirPath) {
        Objects.requireNonNull(resource, "resource");
        PathParts parts = split(fhirPath);
        try {
            return resolve(resource, parts.path());
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
     * The child element names a model child definition accepts. Real choice elements
     * are already reported under their concrete names by HAPI's
     * {@link BaseRuntimeChildDefinition#getValidChildNames} (for example
     * {@code deceasedBoolean} rather than {@code value[x]}), extension definitions
     * report a single name, and the internal {@code <name>Resource} alias HAPI
     * registers for reference elements is skipped.
      */
    private static List<String> namesOf(BaseRuntimeChildDefinition child) {
        List<String> names = new ArrayList<>();
        for (String name : child.getValidChildNames()) {
            if (!name.endsWith("[x]") && !name.equals(child.getElementName() + "Resource")) {
                names.add(name);
            }
        }
        return names;
    }

    /** The specification documentation of a model child definition, when available. */
    private static String definitionTextOf(BaseRuntimeChildDefinition child) {
        if (child instanceof BaseRuntimeDeclaredChildDefinition declared) {
            return declared.getShortDefinition();
        }
        return null;
    }

    /** The composite definition of an element, or {@code null} for primitives. */
    private BaseRuntimeElementCompositeDefinition<?> compositeDefinitionOf(IBase element) {
        try {
            if (context.getElementDefinition(element.getClass())
                    instanceof BaseRuntimeElementCompositeDefinition<?> composite) {
                return composite;
            }
        } catch (RuntimeException e) {
            // An unknown element class is reported when a change is attempted.
        }
        return null;
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
