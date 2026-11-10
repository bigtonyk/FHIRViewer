package com.spiraleyes.fhirviewer.ui;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import com.spiraleyes.fhirviewer.workspace.WorkspaceMetadata;
import com.spiraleyes.fhirviewer.workspace.WorkspaceRecoveryService;
import com.spiraleyes.fhirviewer.workspace.WorkspaceService;

/**
 * The workspace file actions — New, Open, Save, Save As — the questions asked before a
 * workspace is replaced or the window closes, and the change signal the workspace needs to
 * know it has something to save.
 *
 * <p>These lived inside {@code MainWindow}, which also builds the layout, the menus, the
 * document tabs and the server screens. Moved out unchanged: the same dialogs, the same
 * status lines, the same rule that a cancelled save keeps the window open.</p>
 *
 * <p>The recovery service belongs here rather than to the window because every one of its
 * decisions is a workspace-file decision, and it asks through this class's prompt. The
 * window drives it through {@link #openLastWorkspace()} at start-up and
 * {@link #markWorkspaceChanged()} wherever it changes state a workspace captures — a
 * service nothing calls decides nothing.</p>
 */
final class WorkspaceActions {

    /** Shown in the title bar of this class's dialogs, matching the application's own. */
    private static final String APPLICATION_TITLE = "FHIR Resource Viewer";

    /** The extension a workspace archive carries, with the dot. */
    private static final String WORKSPACE_EXTENSION = ".fhirworkspace";

    /**
     * Moves the recovery snapshots off the home directory, for a portable run or a test.
     *
     * <p>{@code user.home} is right for a normal install and wrong for a test that should not
     * write into it, or for a copy run from a memory stick.
     */
    static final String RECOVERY_HOME_PROPERTY = "fhirviewer.recovery.home";

    private final Stage owner;
    private final WorkspaceService workspaceService;
    private final ThemeManager themeManager;
    private final Consumer<String> status;
    private final WorkspaceRecoveryService recovery;

    /**
     * @param owner            the window the dialogs are modal to
     * @param workspaceService the workspace these actions read and write
     * @param themeManager     source of the dialog stylesheets
     * @param status           where the "Opened workspace: …" style messages go
     */
    WorkspaceActions(Stage owner, WorkspaceService workspaceService, ThemeManager themeManager,
            Consumer<String> status) {
        this.owner = owner;
        this.workspaceService = workspaceService;
        this.themeManager = themeManager;
        this.status = status;
        this.recovery = new WorkspaceRecoveryService(workspaceService,
                recoveryHome(),
                this::confirmClose);
    }

    /** The directory recovery snapshots live beside: {@code user.home} unless moved. */
    static Path recoveryHome() {
        String configured = System.getProperty(RECOVERY_HOME_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    /**
     * Opens the workspace the previous session had open, if its archive is still there.
     *
     * <p>Called once at start-up. It is what makes the rest of this class meaningful: with
     * no workspace open, Save had nothing to write over and the close prompt had no
     * changes of its own to ask about.</p>
     */
    void openLastWorkspace() {
        WorkspaceMetadata opened = workspaceService.openLastFromPreferences();
        if (opened != null) {
            recovery.forgetSnapshot();
            status.accept("Opened the last workspace: " + opened.name());
        }
    }

    /**
     * Records that something a workspace captures has changed — the server list, the
     * installed IG packages — and asks the recovery service for a snapshot of it.
     *
     * <p>The snapshot is taken on the recovery service's own thread, so this is cheap
     * enough to call from the JavaFX thread.</p>
     */
    void markWorkspaceChanged() {
        workspaceService.markDirty();
        recovery.onMeaningfulChange();
    }

    /**
     * Whether a dirty workspace may be closed or replaced.
     *
     * @return {@code true} when there is nothing to lose or the user chose to proceed
     */
    boolean closeDecision() {
        return recovery.handleCloseDecision();
    }

    /** Releases the recovery service's thread once the window is closing. Idempotent. */
    void close() {
        recovery.close();
    }

    /** Replaces the workspace with a new, empty one, asking first when work would be lost. */
    void newWorkspace() {
        if (!recovery.handleCloseDecision()) {
            return;
        }
        workspaceService.newWorkspace("Untitled");
        // The previous workspace's snapshot is not this workspace's history.
        recovery.forgetSnapshot();
        status.accept("New workspace created.");
    }

    /** Opens a workspace file, asking first when work would be lost. */
    void openWorkspace() {
        if (!recovery.handleCloseDecision()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("FHIR Workspace", "*" + WORKSPACE_EXTENSION));
        java.io.File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        try {
            workspaceService.open(file.toPath());
            recovery.forgetSnapshot();
            status.accept("Opened workspace: " + file.getName());
        } catch (Exception ex) {
            status.accept("Failed to open workspace: " + ex.getMessage());
        }
    }

    /** Saves over the workspace's own file, asking where when it has never been saved. */
    void save() {
        save(false);
    }

    /** Always asks where to save. */
    void saveAs() {
        save(true);
    }

    /**
     * Saves the workspace, asking for a file when it has never been saved or when
     * {@code saveAs} is set.
     *
     * @return {@code true} only when the workspace reached disk, so the close prompt can
     *         keep the window open when the save is cancelled or fails
     */
    private boolean save(boolean saveAs) {
        if (!saveAs && workspaceService.getCurrentPath() == null) {
            // Nothing to overwrite yet: the user still has to choose where it goes.
            return save(true);
        }
        Path target = workspaceService.getCurrentPath();
        if (saveAs) {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("FHIR Workspace", "*" + WORKSPACE_EXTENSION));
            java.io.File file = chooser.showSaveDialog(owner);
            if (file == null) {
                return false;
            }
            target = file.toPath();
        }
        try {
            // The first Save of a session has no workspace yet — start-up deliberately does
            // not invent one — so the file it is about to write becomes the workspace. The
            // absence of this made Save fail with "No workspace open" instead of saving.
            if (workspaceService.getCurrent() == null) {
                workspaceService.newWorkspace(workspaceName(target));
            }
            if (saveAs) {
                workspaceService.saveAs(target);
            } else {
                workspaceService.save();
            }
            status.accept("Workspace saved: " + target.getFileName());
            return true;
        } catch (Exception ex) {
            status.accept("Failed to save workspace: " + ex.getMessage());
            return false;
        }
    }

    /** The workspace name for a file: its own name without the archive extension. */
    private static String workspaceName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(WORKSPACE_EXTENSION)
                ? name.substring(0, name.length() - WORKSPACE_EXTENSION.length())
                : name;
    }

    /**
     * Asks what to do about a dirty workspace before it is closed or replaced.
     *
     * <p>Save writes the workspace first and asks where when it has never been saved; a
     * cancelled save returns Cancel, so neither the window nor the workspace changes.
     * Discard and Save both proceed, because both are the user answering the question.</p>
     *
     * @return the user's decision; never {@code null}
     */
    private WorkspaceRecoveryService.CloseDecision confirmClose() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle(APPLICATION_TITLE);
        alert.setHeaderText("Unsaved workspace");
        alert.setContentText("The workspace has changes that have not been saved to a workspace file.");
        style(alert.getDialogPane());

        ButtonType save = new ButtonType("Save Workspace", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) {
            return WorkspaceRecoveryService.CloseDecision.CANCEL;
        }
        if (choice.get() == save && !save(false)) {
            return WorkspaceRecoveryService.CloseDecision.CANCEL;
        }
        return WorkspaceRecoveryService.CloseDecision.PROCEED;
    }

    /** Same stylesheets and width as the window's other dialogs, so it follows the theme. */
    private void style(DialogPane pane) {
        pane.getStylesheets().addAll(themeManager.stylesheets());
        pane.setPrefWidth(620);
    }
}
