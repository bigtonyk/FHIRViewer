package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.hl7.fhir.r4.model.Patient;
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

    @Test
    void undoOffersTheLatestUndoneEntryOnce() {
        WriteJournal journal = new WriteJournal();
        WriteJournal.Entry entry = journal.record(null, "update", "Patient", "1", "v2",
                WriteJournal.Status.SUCCEEDED, "", Duration.ZERO,
                new WriteJournal.Undo("update", "Patient", "1", "v2", new Patient()));

        assertTrue(journal.lastUndoable().isPresent());
        assertEquals(entry, journal.lastUndoable().orElseThrow());
        assertTrue(journal.undoFor(entry).isPresent());

        journal.markUndone(entry);
        assertTrue(journal.isUndone(entry));
        assertTrue(journal.lastUndoable().isEmpty());
    }

    @Test
    void anUndoPlanWithoutTheDataItNeedsIsNotOffered() {
        WriteJournal journal = new WriteJournal();
        journal.record(null, "update", "Patient", "1", "v2",
                WriteJournal.Status.SUCCEEDED, "", Duration.ZERO,
                new WriteJournal.Undo("update", "Patient", "1", "v2", null));

        assertTrue(journal.lastUndoable().isEmpty(),
                "an update without a before-image cannot be undone");
    }

    @Test
    void resolvedUnknownOutcomesLeaveTheReviewQueue() {
        WriteJournal journal = new WriteJournal();
        WriteJournal.Entry entry = journal.record(null, "create", "Patient", null, null,
                WriteJournal.Status.UNKNOWN, "TIMEOUT", Duration.ZERO);

        assertEquals(entry, journal.lastUnresolvedUnknown().orElseThrow());
        journal.markResolved(entry);

        assertTrue(journal.isResolved(entry));
        assertTrue(journal.lastUnresolvedUnknown().isEmpty());
    }

    @Test
    void unresolvedUnknownsListsEveryUnreviewedOutcomeOldestFirst() {
        WriteJournal journal = new WriteJournal();
        journal.record(null, "update", "Patient", "1", "v1",
                WriteJournal.Status.UNKNOWN, "TIMEOUT", Duration.ZERO);
        WriteJournal.Entry created = journal.record(null, "create", "Patient", null, null,
                WriteJournal.Status.UNKNOWN, "TIMEOUT", Duration.ZERO);
        journal.record(null, "delete", "Patient", "2", null,
                WriteJournal.Status.FAILED, "CONFLICT", Duration.ZERO);

        assertEquals(2, journal.unresolvedUnknowns().size(),
                "a plain failure is not something to review");
        assertEquals("update", journal.unresolvedUnknowns().get(0).operation());

        journal.markResolved(created);

        assertEquals(1, journal.unresolvedUnknowns().size());
        assertEquals("update", journal.lastUnresolvedUnknown().orElseThrow().operation());
    }
}