package com.spiraleyes.fhirviewer.service.scripting.api;

/**
 * A failure reported by the scripting API, for example a server that cannot be
 * reached, a resource that could not be read or a write that was refused.
 *
 * <p>It is unchecked so scripts can catch it when they want to and let JShell
 * report it as a snippet exception when they do not. The message is written for
 * a human reading the console, never for a machine parsing it.
 */
public class ScriptApiException extends RuntimeException {

    public ScriptApiException(String message) {
        super(message);
    }

    public ScriptApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
