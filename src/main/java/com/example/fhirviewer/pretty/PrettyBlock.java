package com.example.fhirviewer.pretty;

import java.util.ArrayList;
import java.util.List;

/**
 * A titled section of the Pretty View: a heading, label/value rows and nested
 * child blocks.
 *
 * <p>Child blocks are how the model expresses repeating entries (Name 1, Name 2),
 * backbone elements (Observation.component), contained resources and Bundle
 * entries without any resource specific code.</p>
 *
 * @param title       the section heading, for example <code>Address 1</code>
 * @param rows        the label/value lines of this section
 * @param children    nested blocks shown inside this section
 * @param elementName the FHIR element this section was rendered from, for example
 *                    <code>address</code>; empty when the section groups something
 *                    that has no single element (a nested resource section)
 */
public record PrettyBlock(
        String title,
        String elementName,
        List<PrettyRow> rows,
        List<PrettyBlock> children) {

    public PrettyBlock(String title, List<PrettyRow> rows, List<PrettyBlock> children) {
        this(title, "", rows, children);
    }

    public PrettyBlock {
        title = title == null ? "" : title;
        elementName = elementName == null ? "" : elementName;
        rows = rows == null ? List.of() : List.copyOf(rows);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    /** True when the block has no visible content. */
    public boolean isEmpty() {
        return rows.isEmpty() && children.isEmpty();
    }

    /** Mutable builder used by {@link PrettyModelBuilder} to assemble a block. */
    public static final class Builder {

        private final String title;
        private String elementName = "";
        private final List<PrettyRow> rows = new ArrayList<>();
        private final List<PrettyBlock> children = new ArrayList<>();

        private Builder(String title) {
            this.title = title == null ? "" : title;
        }

        /**
         * Names the FHIR element this block was rendered from, so the UI can jump
         * from the resource tree to this section.
         */
        public Builder elementName(String name) {
            this.elementName = name == null ? "" : name;
            return this;
        }

        public Builder row(String label, String value) {
            rows.add(new PrettyRow(label, value));
            return this;
        }

        public Builder row(String label, String value, String elementName) {
            rows.add(new PrettyRow(label, value, elementName));
            return this;
        }

        public Builder rows(List<PrettyRow> toAdd) {
            if (toAdd != null) {
                rows.addAll(toAdd);
            }
            return this;
        }

        public Builder child(PrettyBlock block) {
            if (block != null) {
                children.add(block);
            }
            return this;
        }

        /** The rows accumulated so far (live list, for the document level snapshot). */
        public List<PrettyRow> rows() {
            return rows;
        }

        /** The child blocks accumulated so far (live list, for the document level snapshot). */
        public List<PrettyBlock> children() {
            return children;
        }

        public PrettyBlock build() {
            return new PrettyBlock(title, elementName, rows, children);
        }
    }
}