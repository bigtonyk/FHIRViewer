package com.example.fhirviewer.model;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * One entry of a FHIR <code>Bundle</code>, as shown in the bundle navigator.
 *
 * @param index        zero based position of the entry inside the bundle
 * @param fullUrl      the entry <code>fullUrl</code>, or {@code null}
 * @param displayName  a label such as <code>Patient/patient-a</code>
 * @param resourceType the FHIR resource type of the entry, or {@code null} when empty
 * @param resource     the entry resource, or {@code null} when the entry has no resource
 * @param childEntries nested entries for Bundle-in-Bundle (may be empty)
 */
public record BundleEntryInfo(
        int index,
        String fullUrl,
        String displayName,
        String resourceType,
        IBaseResource resource,
        List<BundleEntryInfo> childEntries) {

    public BundleEntryInfo {
        childEntries = childEntries == null ? List.of() : List.copyOf(childEntries);
    }

    public static BundleEntryInfo of(int index, String fullUrl, String resourceType, String resourceId, IBaseResource resource) {
        String name;
        if (resourceType == null || resourceType.isBlank()) {
            name = "(no resource)";
        } else if (resourceId == null || resourceId.isBlank()) {
            name = resourceType;
        } else {
            name = resourceType + "/" + resourceId;
        }
        return new BundleEntryInfo(index, fullUrl, name, resourceType, resource, List.of());
    }

    public boolean hasResource() {
        return resource != null;
    }

    /** Text rendered in the bundle list. */
    public String getDisplayText() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(index).append("] ").append(displayName);
        if (fullUrl != null && !fullUrl.isBlank()) {
            sb.append("  (").append(fullUrl).append(')');
        }
        return sb.toString();
    }
}