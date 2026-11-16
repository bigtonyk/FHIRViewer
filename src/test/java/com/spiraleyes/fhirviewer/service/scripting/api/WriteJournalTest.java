package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WriteJournalTest {

    @Test
    void summarySeparatesSuccessFailureUncertaintyAndCancellation() {
        WriteJournal journal = new WriteJournal();
        journal.record(null, "create", "Patient", "1", null,
                WriteJournal.Status.SUCCEEDED, "", Duration.ofMillis(2));
        journal.record(null, "patch", "Patient", "1", "v2",
                WriteJournal.Status.FAILED, "CONFLICT", Duration.ofMillis(3));
        journal.record(null, "delete", "Patient", "1", "v2",
                WriteJournal.Status.UNKNOWN, "TIMEOUT", Duration.ofMillis(4));
        journal.record(null, "delete", "Patient", "1", "v2",
                WriteJournal.Status.CANCELLED, "cancelled", Duration.ofMillis(1));

        assertEquals("Writes: 1 succeeded, 1 failed, 1 outcome unknown, 1 cancelled.",
                WriteJournal.summary(journal.entries()));
    }

    @Test
    void summaryFromAnIndexOnlyCoversTheCurrentRun() {
        WriteJournal journal = new WriteJournal();
        journal.record(null, "create", "Patient", "1", null,
                WriteJournal.Status.SUCCEEDED, "", Duration.ZERO);
        int runStart = journal.size();
        journal.record(null, "update", "Patient", "1", "v2",
                WriteJournal.Status.SUCCEEDED, "", Duration.ZERO);

        assertEquals("Writes: 1 succeeded.",
                WriteJournal.summary(journal.entriesFrom(runStart)));
        assertEquals(2, journal.entries().size());
        assertFalse(journal.entries().get(0).toString().contains("secret"));
    }
}