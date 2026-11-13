package com.spiraleyes.fhirviewer.service.scripting.api;

/**
 * A script tried to create, update or delete a resource while the write policy
 * is {@link WritePolicy#READ_ONLY read-only}. Catch it, or let the console
 * report it.
 */
public class WriteDeniedException extends ScriptApiException {

    public WriteDeniedException(String reason) {
        super(reason);
    }
}
