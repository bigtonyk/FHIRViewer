package com.spiraleyes.fhirviewer.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

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
        return new WorkspaceRecoveryService(workspace, dir, () -> { }, () -> {
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
}
