package com.example.fhirviewer.model;

/**
 * The profile a validation run was performed against, together with the
 * installed package that provides it.
 *
 * @param canonical       the profile canonical URL, for example
 *                        <code>http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient</code>
 * @param title           the profile's human readable title, when known
 * @param packageId       the FHIR package id that provides the profile, for example
 *                        <code>hl7.fhir.us.core</code>
 * @param packageVersion  the exact installed version of that package
 */
public record ValidationProfile(
        String canonical,
        String title,
        String packageId,
        String packageVersion) {

    public ValidationProfile {
        canonical = canonical == null ? "" : canonical;
        title = title == null ? "" : title;
        packageId = packageId == null ? "" : packageId;
        packageVersion = packageVersion == null ? "" : packageVersion;
    }

    /**
     * A short label for the results list, for example
     * <code>US Core Patient 9.0.0</code>, falling back to the canonical URL
     * when no title is known.
     */
    public String label() {
        String name = title.isEmpty() ? shortenCanonical(canonical) : title;
        if (packageVersion.isEmpty()) {
            return name;
        }
        return name + " " + packageVersion;
    }

    /** The package id and version, for example <code>hl7.fhir.us.core 9.0.0</code>. */
    public String packageLabel() {
        if (packageId.isEmpty()) {
            return "";
        }
        return packageVersion.isEmpty() ? packageId : packageId + " " + packageVersion;
    }

    /** Last path segment of a canonical URL, used when no title is available. */
    private static String shortenCanonical(String canonical) {
        if (canonical.isEmpty()) {
            return "";
        }
        int slash = canonical.lastIndexOf('/');
        return slash < 0 || slash == canonical.length() - 1
                ? canonical
                : canonical.substring(slash + 1);
    }
}
