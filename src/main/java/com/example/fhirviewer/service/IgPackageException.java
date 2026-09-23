// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.io.IOException;

/**
 * A package could not be loaded. The {@link Kind} lets the UI show a friendly
 * message instead of a raw stack trace (Update 18).
 */
public class IgPackageException extends IOException {

    /** Why a package could not be used. */
    public enum Kind {
        /** The archive is missing or unreadable. */
        UNREADABLE,
        /** The archive is not a FHIR NPM package. */
        CORRUPT,
        /** The package targets a FHIR version this build cannot validate. */
        UNSUPPORTED_FHIR_VERSION,
        /** A declared dependency is not installed. */
        MISSING_DEPENDENCY
    }

    private final Kind kind;

    public IgPackageException(Kind kind, String message) {
        this(kind, message, null);
    }

    public IgPackageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /** A message suitable for display to a user. */
    public String getUserMessage() {
        return getMessage();
    }
}
