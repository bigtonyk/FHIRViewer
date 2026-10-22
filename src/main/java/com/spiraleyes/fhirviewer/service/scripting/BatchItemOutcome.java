package com.spiraleyes.fhirviewer.service.scripting;

/**
 * The outcome of processing one item in a batch.
 */
public enum BatchItemOutcome {
    /** The item was processed successfully. */
    PROCESSED,
    /** The item's action threw an exception or otherwise failed. */
    FAILED,
    /** The item was skipped (for example, because the batch was cancelled). */
    SKIPPED
}
