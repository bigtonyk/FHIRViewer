package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.ServerDefinition;

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

    @Test
    void anActiveRunDoesNotExpireAndEachAttemptRefreshesTheIdleDeadline() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        WritePolicy policy = WritePolicy.enabled(null, null, new WriteJournal(), 0, null,
                Duration.ofMillis(100), now::get);

        policy.beginRun();
        now.addAndGet(200_000_000L);

        assertTrue(policy.isEnabled(), "an active run must survive its idle deadline");
        policy.requireWrite(null, "create");
        policy.endRun();

        now.addAndGet(80_000_000L);
        assertTrue(policy.isEnabled(), "the last write should start a fresh deadline");
        policy.requireWrite(null, "update");
        now.addAndGet(80_000_000L);
        assertTrue(policy.isEnabled(), "every permitted attempt should refresh the deadline");

        now.addAndGet(30_000_000L);
        assertFalse(policy.isEnabled(), "the grant should expire after the refreshed deadline");
    }

    @Test
    void anOperationScopeRefusesWritesOutsideIt() {
        WritePolicy policy = WritePolicy.enabled(null, null, null, 0, null, null,
                WriteScope.operations(WriteScope.Operation.UPDATE));

        policy.requireWrite(null, "update", "Patient");
        assertThrows(WriteDeniedException.class, () -> policy.requireWrite(null, "create", "Patient"));
    }

    @Test
    void aResourceTypeScopeRefusesOtherTypes() {
        WritePolicy policy = WritePolicy.enabled(null, null, null, 0, null, null,
                WriteScope.of(Set.of(WriteScope.Operation.UPDATE), Set.of("Patient")));

        policy.requireWrite(null, "update", "Patient");
        assertThrows(WriteDeniedException.class, () -> policy.requireWrite(null, "update", "Observation"));
    }

    @Test
    void aReadOnlyServerRefusesWritesEvenWithAnEnabledGrant() {
        ServerDefinition readOnly = ServerDefinition.named("Read Only",
                "https://readonly.example.org/fhir").writeProtected(true).build();
        WritePolicy policy = WritePolicy.enabled();

        assertThrows(WriteDeniedException.class,
                () -> policy.requireWrite(readOnly, "update", "Patient"));
    }

    @Test
    void aWriteWithItsOwnAuthorizationStillNeedsTheGrantAndItsScope() {
        assertThrows(WriteDeniedException.class, () -> WritePolicy.READ_ONLY
                .requireWriteWithItsOwnAuthorization("create", "Patient", "the SMART session"));

        WritePolicy policy = WritePolicy.enabled(null, null, null, 0, null, null,
                WriteScope.operations(WriteScope.Operation.CREATE));
        policy.requireWriteWithItsOwnAuthorization("create", "Patient", "the SMART session");
        assertThrows(WriteDeniedException.class, () -> policy
                .requireWriteWithItsOwnAuthorization("update", "Patient", "the SMART session"));
    }

    @Test
    void aWriteWithItsOwnAuthorizationChargesTheBudget() {
        WritePolicy policy = WritePolicy.enabled(null, null, null, 1, null, null);

        policy.requireWriteWithItsOwnAuthorization("create", "Patient", "the SMART session");

        assertThrows(WriteDeniedException.class, () -> policy
                .requireWriteWithItsOwnAuthorization("create", "Patient", "the SMART session"));
    }

    @Test
    void aGrantBoundToAConfiguredServerStillAllowsASessionWrite() {
        ServerDefinition approved = ServerDefinition.named("Approved",
                "https://approved.example.org/fhir").build();
        WritePolicy policy = WritePolicy.enabled(null, approved, null, 0, null, null);

        // The binding still applies to the saved-credential path...
        policy.requireWrite(approved, "create", "Patient");
        // ...but a session carries its own authorization for its own server, so the
        // console's server binding is not what decides that write.
        policy.requireWriteWithItsOwnAuthorization("create", "Patient", "the SMART session");
    }

    @Test
    void startingARunDoesNotItselfRefreshTheDeadline() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        WritePolicy policy = WritePolicy.enabled(null, null, null, 0, null,
                Duration.ofMillis(100), now::get);

        now.addAndGet(90_000_000L);
        policy.beginRun();
        policy.endRun();
        now.addAndGet(20_000_000L);

        assertFalse(policy.isEnabled(),
                "only a write should move the idle deadline; starting a run must not");
    }
}
