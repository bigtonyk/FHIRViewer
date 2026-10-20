package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WritePolicyTest {

    @Test
    void readOnlyRefusesWrites() {
        assertFalse(WritePolicy.READ_ONLY.isEnabled());
        assertFalse(WritePolicy.READ_ONLY.reason().isEmpty());
        assertThrows(WriteDeniedException.class, WritePolicy.READ_ONLY::requireWrite);
    }

    @Test
    void enabledPermitsWrites() {
        WritePolicy enabled = WritePolicy.enabled();
        assertTrue(enabled.isEnabled());
        assertTrue(enabled.reason().isEmpty());
        enabled.requireWrite(); // does not throw
    }
}
