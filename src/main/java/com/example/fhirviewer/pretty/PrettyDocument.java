package com.example.fhirviewer.pretty;

import java.util.List;

/**
 * The complete presentation model of one resource for the Pretty View: the
 * resource header (type, id, metadata), document level summary rows and the
 * top level sections.
 *
 * <p>Produced by {@link PrettyModelBuilder}; rendered by the JavaFX
 * {@code PrettyView}. A plain data type so tests can assert on the presentation
 * without a running JavaFX toolkit.</p>
 *
 * @param resourceType the FHIR resource type, for example <code>Patient</code>
 * @param resourceId   the logical id, or {@code null}
 * @param headerRows   resource level metadata such as <code>Language</code> and
 *                     <code>Profiles</code>
 * @param summaryRows  document level label/value rows for top level primitive
 *                     elements, for example <code>Gender: male</code>
 * @param sections     the top level sections of the document
 */
public record PrettyDocument(
        String resourceType,
        String resourceId,
        List<PrettyRow> headerRows,
        List<PrettyRow> summaryRows,
        List<PrettyBlock> sections) {

    public PrettyDocument {
        resourceType = resourceType == null ? "" : resourceType;
        headerRows = headerRows == null ? List.of() : List.copyOf(headerRows);
        summaryRows = summaryRows == null ? List.of() : List.copyOf(summaryRows);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** The document level rows, identical to {@code summaryRows()}; kept short for readable tests. */
    public List<PrettyRow> rows() {
        return summaryRows;
    }
}