package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.spiraleyes.fhirviewer.workspace.WorkspaceService;

/**
 * The workspace wiring between the window's file actions and the service behind them.
 *
 * <p>This is the test that was missing when H6 happened: nothing anywhere built these two
 * classes together, so four links in the chain (a service that never opened anything, a
 * dirty flag nobody set, snapshots nobody took, a Save that could only fail) stayed invisible
 * through a fully green suite. It asserts the wiring, not the dialogs: the paths that need a
 * {@code FileChooser} or a modal prompt are exactly the ones a headless test cannot drive.</p>
 *
 * <p>The stage is {@code null} because nothing here opens a dialog - a clean workspace's
 * close decision and the start-up open are both decided without one. Preferences are the
 * application's real {@code workspace} node, so each test snapshots and restores it: a test
 * run must not decide what the developer's next session reopens.</p>
 */
class WorkspaceWiringTest {

    @TempDir
    Path dir;

    private String originalHome;
    private Map<String, String> savedPreferences;

    @BeforeEach
    void isolateTheRun() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.resolve("home").toString());

        Preferences node = workspacePreferences();
        savedPreferences = new LinkedHashMap<>();
        for (String key : node.keys()) {
            savedPreferences.put(key, node.get(key, ""));
        }
        node.clear();
        node.flush();
    }

    @AfterEach
    void restoreTheRun() throws Exception {
        Preferences node = workspacePreferences();
        node.clear();
        savedPreferences.forEach((key, value) -> node.put(key, value));
        node.flush();

        if (originalHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @DisplayName("Start-up opens the workspace the previous session left behind")
    void startUpOpensTheRecordedWorkspace() throws Exception {
        Path archive = recordedWorkspace("Smoke Workspace");

        WorkspaceService service = new WorkspaceService();
        List<String> messages = new ArrayList<>();
        WorkspaceActions actions = actionsFor(service, messages);

        actions.openLastWorkspace();

        assertNotNull(service.getCurrent(),
                "start-up must open the workspace, or Save has nothing to write over");
        assertEquals("Smoke Workspace", service.getCurrent().name());
        assertEquals(archive, service.getCurrentPath());
        assertFalse(service.isDirty(), "merely opening a workspace must not make it dirty");
        assertTrue(messages.stream().anyMatch(m -> m.contains("last workspace")),
                messages.toString());
    }

    @Test
    @DisplayName("Start-up with nothing recorded invents no workspace")
    void startUpWithoutARecordedWorkspaceOpensNothing() {
        WorkspaceService service = new WorkspaceService();
        List<String> messages = new ArrayList<>();

        actionsFor(service, messages).openLastWorkspace();

        assertNull(service.getCurrent(),
                "a fresh session must start with no workspace, so the first Save asks where");
        assertEquals(List.of(), messages);
    }

    @Test
    @DisplayName("A recorded workspace whose archive has moved is forgotten")
    void aMissingArchiveIsForgotten() throws Exception {
        Path gone = dir.resolve("moved-away.fhirworkspace");
        Preferences node = workspacePreferences();
        node.put("lastPath", gone.toString());
        node.put("lastId", "0f0f0f0f-0000-0000-0000-000000000000");
        node.put("lastName", "Gone");
        node.flush();

        WorkspaceService service = new WorkspaceService();
        actionsFor(service, new ArrayList<>()).openLastWorkspace();

        assertNull(service.getCurrent());
        assertNull(node.get("lastPath", null),
                "a path that no longer exists must not be offered again next time");
    }

    @Test
    @DisplayName("A change the workspace captures makes it savable")
    void aWorkspaceChangeMarksItDirty() throws Exception {
        recordedWorkspace("Changes");
        WorkspaceService service = new WorkspaceService();
        service.openLastFromPreferences();
        assertFalse(service.isDirty());

        actionsFor(service, new ArrayList<>()).markWorkspaceChanged();

        assertTrue(service.isDirty(),
                "a server or package change must give the workspace something to save");
    }

    @Test
    @DisplayName("Closing a clean workspace asks nothing")
    void closingACleanWorkspaceAsksNothing() throws Exception {
        recordedWorkspace("Clean");
        WorkspaceService service = new WorkspaceService();
        service.openLastFromPreferences();

        // A prompt here would open a modal dialog; the test would hang rather than pass.
        assertTrue(actionsFor(service, new ArrayList<>()).closeDecision());
    }

    /** Writes a workspace archive and records it as the previous session's, as a save would. */
    private Path recordedWorkspace(String name) throws Exception {
        WorkspaceService saved = new WorkspaceService();
        saved.newWorkspace(name);
        Path archive = dir.resolve(name.replace(' ', '-') + ".fhirworkspace");
        saved.saveAs(archive);
        assertTrue(Files.isRegularFile(archive), "the archive must exist for the test to test");
        return archive;
    }

    private static WorkspaceActions actionsFor(WorkspaceService service, List<String> messages) {
        return new WorkspaceActions(null, service, new ThemeManager(), messages::add);
    }

    private static Preferences workspacePreferences() throws Exception {
        return Preferences.userRoot().node("workspace");
    }
}
