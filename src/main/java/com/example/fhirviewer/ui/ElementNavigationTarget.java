package com.example.fhirviewer.ui;

import com.example.fhirviewer.model.ResourceNode;

/**
 * A document tab view (Pretty, JSON, XML) that can jump to the on-screen
 * representation of a resource element when the user selects that element in
 * the left resource tree.
 *
 * <p>Implementations are best effort: they scroll the view so the matching
 * element is visible, or do nothing when the element cannot be located (for
 * example when no resource is loaded).</p>
 */
public interface ElementNavigationTarget {

    /**
     * Scrolls the view so that the supplied element's content is visible.
     *
     * @param node the element selected in the resource tree, or {@code null}
     */
    void scrollToElement(ResourceNode node);
}
