package com.example.fhirviewer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A node in the generic FHIR resource tree.
 *
 * <p>A node wraps {@link ElementInfo} (the element's metadata) plus the element's
 * children. Nodes are produced dynamically from the FHIR model by
 * <code>com.example.fhirviewer.fhir.ResourceTreeBuilder</code>; no FHIR resource
 * type is hard-coded anywhere in the application.</p>
 */
public final class ResourceNode {

    private final String label;
    private final ElementInfo info;
    private final String valueText;
    private final List<ResourceNode> children = new ArrayList<>();
    private ResourceNode parent;

    public ResourceNode(String label, ElementInfo info) {
        this(label, info, null);
    }

    public ResourceNode(String label, ElementInfo info, String valueText) {
        this.label = Objects.requireNonNull(label, "label");
        this.info = Objects.requireNonNull(info, "info");
        this.valueText = valueText;
    }

    public String getLabel() {
        return label;
    }

    public ElementInfo getElementInfo() {
        return info;
    }

    public String getPath() {
        return info.getPath();
    }

    /** The primitive value rendered for this node, or {@code null}. */
    public String getValueText() {
        return valueText;
    }

    public void addChild(ResourceNode child) {
        Objects.requireNonNull(child, "child");
        child.parent = this;
        children.add(child);
    }

    /** The parent node, or {@code null} for the root of the tree. */
    public ResourceNode getParent() {
        return parent;
    }

    public List<ResourceNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public int getDepth() {
        int depth = 0;
        ResourceNode current = parent;
        while (current != null) {
            depth++;
            current = current.getParent();
        }
        return depth;
    }

    /**
     * Text rendered for this node in the tree view. Primitive values are shown
     * inline after the element name, for example <code>family: Smith</code>.
     */
    public String getDisplayText() {
        StringBuilder sb = new StringBuilder(label);
        if (valueText != null && !valueText.isEmpty()) {
            sb.append(": ").append(valueText);
        } else if (isLeaf() && info.getKind() == ElementInfo.Kind.COMPLEX) {
            sb.append(" (empty)");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getDisplayText();
    }
}