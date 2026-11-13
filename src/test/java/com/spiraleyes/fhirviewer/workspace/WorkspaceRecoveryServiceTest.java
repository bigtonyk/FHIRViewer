package com.spiraleyes.fhirviewer.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the close decision contract: a dirty workspace can actually say no.
 *
 * <p>Every path of the old {@code handleCloseDecision()} returned {@code true}, so no
 * prompt could keep the window open. These tests pin the answer to the user's choice.</p>
 */
class WorkspaceRecoveryServiceTest {

    @TempDir
    Path dir;

    private WorkspaceRecoveryService recovery(WorkspaceService workspace,
            WorkspaceRecoveryService.CloseDecision answer, AtomicBoolean asked) {
        return new WorkspaceRecoveryService(workspace, dir, () -> {
            asked.set(true);
            return answer;
        });
    }

    @Test
    @DisplayName("A clean workspace closes without asking")
    void cleanWorkspaceDoesNotAsk() {
        AtomicBoolean asked = new AtomicBoolean();
        WorkspaceRecoveryService recovery = recovery(new WorkspaceService(),
                WorkspaceRecoveryService.CloseDecision.CANCEL, asked);

        assertTrue(recovery.handleCloseDecision(), "a clean workspace may close");
        assertFalse(asked.get(), "there is nothing to ask about");
    }

    @Test
    @DisplayName("A dirty workspace stays open when the user cancels")
    void cancelledDecisionKeepsTheWorkspace() {
        WorkspaceService workspace = new WorkspaceService();
        workspace.newWorkspace("Unsaved");
        AtomicBoolean asked = new AtomicBoolean();
        WorkspaceRecoveryService recovery = recovery(workspace,
                WorkspaceRecoveryService.CloseDecision.CANCEL, asked);

        assertFalse(recovery.handleCloseDecision(), "Cancel must stop the close");
        assertTrue(asked.get(), "a dirty workspace must be asked about");
    }

    @Test
    @DisplayName("A dirty workspace closes when the user discards or saves")
    void proceededDecisionCloses() {
        WorkspaceService workspace = new WorkspaceService();
        workspace.newWorkspace("Unsaved");
        AtomicBoolean asked = new AtomicBoolean();
        WorkspaceRecoveryService recovery = recovery(workspace,
                WorkspaceRecoveryService.CloseDecision.PROCEED, asked);

        assertTrue(recovery.handleCloseDecision(), "Proceed must allow the close");
        assertTrue(asked.get());
    }

    @Test
    @DisplayName("Saving leaves no snapshot to offer back")
    void savingLeavesNoSnapshotToRestore() throws Exception {
        WorkspaceService workspace = new WorkspaceService();
        workspace.newWorkspace("Saved");
        Path archive = dir.resolve("saved.fhirworkspace");
        workspace.saveAs(archive);
        workspace.markDirty();
        WorkspaceRecoveryService recovery = recovery(workspace,
                WorkspaceRecoveryService.CloseDecision.PROCEED, new AtomicBoolean());

        recovery.saveCurrentArchive();
        recovery.close();

        assertTrue(Files.isRegularFile(archive), "the save must reach disk");
        assertFalse(workspace.isDirty(), "a saved workspace has nothing to lose");
        assertFalse(recovery.hasRecoverySnapshot(),
                "the state the save replaced must not come back as a recovery point");
        assertEquals(List.of(), snapshotFiles());
    }

    @Test
    @DisplayName("A queued snapshot is discarded when the baseline is reset")
    void aQueuedSnapshotIsDiscardedByAForget() throws Exception {
        WorkspaceService workspace = new WorkspaceService();
        workspace.newWorkspace("Changed");
        WorkspaceRecoveryService recovery = recovery(workspace,
                WorkspaceRecoveryService.CloseDecision.PROCEED, new AtomicBoolean());

        recovery.takeSnapshotNow();
        recovery.forgetSnapshot();
        // close() waits for the thread, so the outcome is settled before asserting.
        recovery.close();

        assertFalse(recovery.hasRecoverySnapshot(),
                "clearing the path must also disown the snapshot still in flight");
        assertEquals(List.of(), snapshotFiles());
    }

    @Test
    @DisplayName("A snapshot that finished before a save is deleted by it")
    void aSnapshotQueuedBeforeASaveIsNotOfferedAfterwards() throws Exception {
        WorkspaceService workspace = new WorkspaceService();
        workspace.newWorkspace("Edited");
        workspace.saveAs(dir.resolve("edited.fhirworkspace"));
        workspace.markDirty();
        WorkspaceRecoveryService recovery = recovery(workspace,
                WorkspaceRecoveryService.CloseDecision.PROCEED, new AtomicBoolean());

        recovery.takeSnapshotNow();
        // Wait for the file instead of racing it: the assertion is about a snapshot that
        // already exists when the save forgets it, not about which thread ran first.
        awaitCompletedSnapshot(recovery);
        recovery.saveCurrentArchive();

        assertEquals(List.of(), snapshotFiles(), "the save must delete the snapshot it replaced");
        recovery.close();

        assertFalse(recovery.hasRecoverySnapshot());
        assertEquals(List.of(), snapshotFiles());
    }

    @Test
    @DisplayName("Closing stops the snapshot thread and keeps what it finished")
    void closeKeepsTheCompletedSnapshot() throws Exception {
        WorkspaceService workspace = new WorkspaceService();
        workspace.newWorkspace("Live");
        WorkspaceRecoveryService recovery = recovery(workspace,
                WorkspaceRecoveryService.CloseDecision.PROCEED, new AtomicBoolean());

        recovery.takeSnapshotNow();
        recovery.close();
        recovery.close();
        recovery.takeSnapshotNow();

        assertTrue(recovery.hasRecoverySnapshot(), "a snapshot taken before closing survives");
        assertEquals(1, snapshotFiles().size());
        assertTrue(recovery.restoreFromLastSnapshot(), "and can still be restored");
        assertTrue(workspace.isDirty(), "a restored workspace has changes to save");
    }

    /** Waits until the queued snapshot has been written and registered. */
    private void awaitCompletedSnapshot(WorkspaceRecoveryService recovery) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!recovery.hasRecoverySnapshot() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(recovery.hasRecoverySnapshot(), "the snapshot should reach disk before the save");
    }

    /** The {@code .snap} files in the recovery directory this test's service writes to. */
    private List<Path> snapshotFiles() throws Exception {
        try (Stream<Path> entries = Files.list(dir.resolve(".fhirviewer-recovery"))) {
            return entries.map(Path::getFileName).sorted().toList();
        }
    }
}
