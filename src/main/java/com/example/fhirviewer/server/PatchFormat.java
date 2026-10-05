package com.example.fhirviewer.server;

import java.util.Locale;
import java.util.Optional;

/**
 * The patch formats a FHIR server may accept on {@code PATCH [type]/[id]}.
 *
 * <p>Which format a patch is written in is not a detail of the body: it is carried
 * entirely by {@code Content-Type}, and a server that receives a JSON Patch under
 * {@code application/merge-patch+json} will either mis-apply it or refuse it. The
 * format is therefore an explicit choice the caller makes here rather than something
 * inferred later, and it is sent as the content type on the request.
 *
 * <p>Only the three body-shaped formats are here. A FHIRPath patch is a different
 * shape — a {@code Parameters} resource rather than a patch document — and adding it
 * would need a request that carries a resource, not a string, so it is deliberately
 * left out rather than approximated by sending {@code Parameters} as a merge patch.
 */
public enum PatchFormat {

    /** RFC 6902, an array of operations: {@code application/json-patch+json}. */
    JSON_PATCH("application/json-patch+json"),

    /** RFC 7386, a partial object: {@code application/merge-patch+json}. */
    JSON_MERGE_PATCH("application/merge-patch+json"),

    /** The XML equivalent of a JSON patch: {@code application/xml-patch+xml}. */
    XML_PATCH("application/xml-patch+xml");

    private final String contentType;

    PatchFormat(String contentType) {
        this.contentType = contentType;
    }

    /** The {@code Content-Type} to send this patch as. */
    public String contentType() {
        return contentType;
    }

    /** True when this format's body is JSON, which decides which parser reads the reply. */
    public boolean isJson() {
        return this != XML_PATCH;
    }

    /** The format a server or caller named, or empty when the type is not one of ours. */
    public static Optional<PatchFormat> forContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        // Servers routinely add parameters, e.g. "application/merge-patch+json; charset=utf-8".
        String bare = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        for (PatchFormat candidate : values()) {
            if (candidate.contentType.equals(bare)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
