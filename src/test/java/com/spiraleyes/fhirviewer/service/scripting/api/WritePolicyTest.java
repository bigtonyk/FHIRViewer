package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

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

    @Test
    void revocationTurnsOffEveryReferenceThatHoldsThePolicy() {
        WritePolicy policy = WritePolicy.enabled();

        policy.revoke();

        assertFalse(policy.isEnabled());
        assertThrows(WriteDeniedException.class, policy::requireWrite);
    }

    @Test
    void anIdleGrantExpiresEvenBeforeTheUiChecksIt() {
        WritePolicy policy = WritePolicy.enabled(null, null, null, 0, null, Duration.ZERO);

        assertFalse(policy.isEnabled());
        assertThrows(WriteDeniedException.class, policy::requireWrite);
    }

    @Test
    void aBudgetWithoutAConfirmationRefusesTheNextBatch() {
        WritePolicy policy = WritePolicy.enabled(null, null, new WriteJournal(), 1, null, null);

        policy.requireWrite(null, "create");
        assertThrows(WriteDeniedException.class, () -> policy.requireWrite(null, "create"));
    }
}
