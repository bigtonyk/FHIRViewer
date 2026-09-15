package com.example.fhirviewer.service;

/**
 * Signals that a resource file (or sample) could not be read or parsed.
 *
 * <p>The message is written for the user: the UI shows it in the status bar and in
 * the error dialog.</p>
 */
public class ResourceLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}