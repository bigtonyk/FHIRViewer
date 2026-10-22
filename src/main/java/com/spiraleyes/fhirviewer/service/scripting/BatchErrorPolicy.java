package com.spiraleyes.fhirviewer.service.scripting;

/**
 * How a batch processor behaves when an item's action fails.
 */
public enum BatchErrorPolicy {
    /** Stop processing immediately on the first failed item; later items are skipped. */
    STOP_ON_FIRST_ERROR,
    /** Continue processing remaining items after a failure, recording each outcome. */
    CONTINUE
}
