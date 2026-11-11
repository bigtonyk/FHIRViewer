package com.spiraleyes.fhirviewer.ui;

import java.util.function.Consumer;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ResourceFormat;
import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.server.ServerWriteResult;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

/**
 * The actions on a resource that lives on a server: save it, patch it, delete it, reload it, and
 * answer the version-conflict question when the server copy moved.
 *
 * <p>Extracted from {@code MainWindow} as the third step of the M1 split (R4 §7). The rules
 * themselves are in {@link ServerResourceCoordinator}; what moves here is the plumbing around
 * them — the dialogs, the background threads and the outcome handling. The window is reached
 * through {@link DocumentPort}, which is the document-state seam the plan calls for: show a
 * resource, ask before discarding one, clear it, report validation, and set the busy cursor.</p>
 *
 * <p>Behaviour is unchanged, thread hops included: every network call still runs on a daemon
 * thread and returns through {@link Platform#runLater}.</p>
 */
final class ServerResourceActions {

    private final Stage owner;
    private final ServerResourceCoordinator coordinator;
    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    private final ThemeManager themeManager;
    private final Consumer<String> status;
    private final DocumentPort document;

    ServerResourceActions(Stage owner, ServerResourceCoordinator coordinator,
            FhirServerService serverService, FhirServerManager serverManager,
            ThemeManager themeManager, Consumer<String> status, DocumentPort document) {
        this.owner = owner;
        this.coordinator = coordinator;
        this.serverService = serverService;
        this.serverManager = serverManager;
        this.themeManager = themeManager;
        this.status = status;
        this.document = document;
    }

    /** Finds the loaded server an origin came from, or {@code null} when it is gone. */
    FhirServerConfiguration serverFor(ServerOrigin origin) {
        if (origin == null) {
            return null;
        }
        for (FhirServerConfiguration server : serverManager.servers()) {
            // Compared field by field rather than via origin.isSameServer, which takes
            // another origin; here we are matching a server definition.
            if (origin.pluginId().equals(server.pluginId())
                    && origin.baseUrl().equals(server.baseUrl())) {
                return server;
            }
        }
        return null;
    }

    /**
     * Reads {@code meta.versionId} from a resource, for use as the origin's version.
     * Returns {@code null} when the server reported none, which disables the conflict
     * check rather than faking one.
     */
    static String versionOf(IBaseResource resource) {
        if (resource == null || resource.getMeta() == null) {
            return null;
        }
        // IBaseMetaType has no hasVersionId() — getVersionId() returns null when absent.
        return resource.getMeta().getVersionId();
    }

    /**
     * Writes the displayed resource back to the server it came from, or to a chosen
     * server when it did not come from one.
     *
     * <p>The confirmation is {@link SaveToServerDialog}, which replaced a bare
     * server-picker plus a separate alert. One screen now states the target, the verb
     * and the version situation together, so the decision that was split across two
     * dialogs is made in one place.</p>
     *
     * <p>A conflict is surfaced rather than resolved silently, so a second user's work is
     * never overwritten without a decision; the rules themselves live in
     * {@link ServerResourceCoordinator}.</p>
     */
    void save() {
        if (document.resource() == null) {
            status.accept("Nothing to save. Open a FHIR resource first.");
            return;
        }
        ServerOrigin origin = document.origin();
        FhirServerConfiguration preselect = serverFor(origin);
        // IBaseResource has no display name; fhirType() is the type the server addresses
        // it by, and is the fallback when the resource has no origin to read it from.
        String resourceType = origin != null ? origin.resourceType() : document.resource().fhirType();
        SaveToServerDialog dialog = new SaveToServerDialog(serverManager.servers(), preselect,
                origin, document.resource(), resourceType);
        dialog.initOwner(owner);
        themeDialog(dialog.getDialogPane());
        SaveToServerDialog.Plan plan = dialog.showAndWait().orElse(null);
        if (plan == null) {
            return;
        }
        runServerWrite(plan.server(), plan.force());
    }

    /**
     * Performs the write on a background thread and reports the outcome back on the UI
     * thread.
     *
     * <p>The network call must not run on the JavaFX thread or the window freezes for the
     * duration, so the same plain daemon {@link Thread} pattern used for the IG package
     * auto-load is used here.</p>
     *
     * <p>Every decision is delegated to {@link ServerResourceCoordinator}, which owns the
     * validate-then-write rule, the create-versus-update choice, the conflict outcome and
     * the rebased origin. This method only moves the result onto the JavaFX thread, so
     * the rules that must not be wrong are testable without a toolkit.</p>
     *
     * @param force when set, the version is dropped so the write goes through even though
     *              the server copy changed
     */
    private void runServerWrite(FhirServerConfiguration target, boolean force) {
        IBaseResource resource = document.resource();
        ServerOrigin origin = document.origin();
        status.accept(force ? "Overwriting on " + target.name() + "..." : "Saving to " + target.name() + "...");
        document.busy(true);
        // The write is a network call and must not run on the JavaFX thread, or the
        // window freezes for its whole duration. The coordinator is deliberately free of
        // JavaFX, so it can be called straight from this worker and its value handed
        // back to the UI thread.
        Thread worker = new Thread(() -> {
            ServerResourceCoordinator.PushResult result;
            try {
                result = force
                        ? coordinator.forceWrite(target, resource, origin)
                        : coordinator.push(target, resource, origin);
            } catch (RuntimeException unexpected) {
                result = new ServerResourceCoordinator.PushResult(
                        ServerResourceCoordinator.Outcome.FAILED, origin, null,
                        ServerErrors.describe(unexpected), null);
            }
            ServerResourceCoordinator.PushResult outcome = result;
            Platform.runLater(() -> onServerWriteFinished(target, resource, outcome));
        }, "server-write");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Reports what the coordinator decided and updates the editor.
     *
     * <p>Handles each outcome the way it deserves: a write re-bases the origin so the
     * <em>next</em> save's {@code If-Match} compares against the right version; a
     * conflict is escalated to a user decision rather than resolved; a validation block
     * shows the issues in the validation panel so they can actually be fixed.</p>
     */
    private void onServerWriteFinished(FhirServerConfiguration target, IBaseResource resource,
            ServerResourceCoordinator.PushResult result) {
        document.busy(false);
        if (result.isConflict()) {
            resolveConflict(target, resource, result.origin());
            return;
        }
        if (result.report() != null) {
            // Show the issues whenever validation ran, not only on success: a resource
            // that was written with warnings should still show them.
            document.showValidation(result.report());
        }
        if (!result.written()) {
            status.accept(result.message());
            return;
        }
        ServerOrigin rebased = result.origin();
        document.show(new LoadedResource(resource, ResourceFormat.JSON,
                rebased.resourceType() + "/" + rebased.resourceId(),
                OpenFromServerDialog.serverLabel(target)), rebased);
        status.accept(result.message());
    }

    /**
     * Handles a version conflict by asking the user to choose, rather than picking for them.
     *
     * <p>Two answers are offered and there is deliberately no default: reload discards the
     * local edit, force overwrites whatever the other user did. Choosing silently either way
     * is how two people lose each other's work, so the dialog makes the cost of each
     * explicit and waits.</p>
     */
    private void resolveConflict(FhirServerConfiguration target, IBaseResource resource,
            ServerOrigin origin) {
        if (origin == null || !origin.isSaved()) {
            status.accept("Not saved: the server copy changed. Nothing was written.");
            return;
        }
        ButtonType reload = new ButtonType("Reload from server", ButtonBar.ButtonData.OK_DONE);
        ButtonType force = new ButtonType("Overwrite anyway", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Save conflict");
        alert.setHeaderText(origin.resourceType() + "/" + origin.resourceId()
                + " changed on the server since you loaded it.");
        alert.setContentText("Server: " + target.name() + "\n" + target.baseUrl()
                + "\n\nReloading discards your unsaved changes and shows the server's version."
                + "\nOverwriting replaces the server's version with yours.");
        alert.getButtonTypes().setAll(reload, force, ButtonType.CANCEL);
        ButtonType answer = alert.showAndWait().orElse(ButtonType.CANCEL);

        if (reload.equals(answer)) {
            reloadFromServer(target, origin);
        } else if (force.equals(answer)) {
            // The coordinator drops the version, so the update goes without If-Match.
            runServerWrite(target, true);
        } else {
            status.accept("Not saved: the server copy changed. Nothing was written.");
        }
    }

    /** Re-reads one resource from the server and shows it, discarding the local edit. */
    private void reloadFromServer(FhirServerConfiguration target, ServerOrigin origin) {
        status.accept("Reloading " + origin.resourceType() + "/" + origin.resourceId() + "...");
        document.busy(true);
        Thread worker = new Thread(() -> {
            IBaseResource reloaded = null;
            Throwable failure = null;
            try {
                reloaded = serverService.read(target, origin.resourceType(), origin.resourceId());
            } catch (Throwable problem) {
                failure = problem;
            }
            IBaseResource fresh = reloaded;
            Throwable error = failure;
            Platform.runLater(() -> {
                document.busy(false);
                if (error != null || fresh == null) {
                    status.accept("Could not reload: " + ServerErrors.describe(error));
                    return;
                }
                ServerOrigin refreshed = ServerOrigin.of(
                        target.pluginId(), target.baseUrl(), origin.resourceType(),
                        origin.resourceId(), versionOf(fresh));
                document.show(new LoadedResource(fresh, ResourceFormat.JSON,
                        refreshed.resourceType() + "/" + refreshed.resourceId(),
                        OpenFromServerDialog.serverLabel(target)), refreshed);
                status.accept("Reloaded " + refreshed.resourceType() + "/"
                        + refreshed.resourceId() + " from " + target.name() + ".");
            });
        }, "server-reload");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Re-reads the displayed resource from the server it came from.
     *
     * <p>The "Refresh" action from the plan. It deliberately discards local edits, so it
     * asks first — silently throwing away unsaved work because a menu item was pressed
     * would be the worst possible reading of the word "refresh".</p>
     */
    void refresh() {
        FhirServerConfiguration target = serverFor(document.origin());
        ServerOrigin origin = document.origin();
        if (target == null || origin == null) {
            status.accept("This resource did not come from a configured server.");
            return;
        }
        if (!document.confirmDiscard("refreshing from the server")) {
            return;
        }
        reloadFromServer(target, origin);
    }

    /**
     * Applies a user-written patch to the displayed resource on the server.
     *
     * <p>The patch is sent as written; this viewer neither rewrites nor validates it,
     * because a merge patch is by definition a partial document that would fail the
     * whole-resource validation a create or an update is held to. What the server makes of
     * it is reported through the same {@link ServerOperationException} path as every other
     * write, so an {@code OperationOutcome} explaining the refusal is what the user sees.</p>
     */
    void patch() {
        FhirServerConfiguration target = serverFor(document.origin());
        ServerOrigin origin = document.origin();
        if (target == null || origin == null) {
            status.accept("This resource did not come from a configured server.");
            return;
        }
        PatchResourceDialog dialog = new PatchResourceDialog(origin, themeManager);
        dialog.initOwner(owner);
        dialog.showAndWait().ifPresent(patch -> runPatch(target, origin, patch));
    }

    /** Sends a patch on a background thread and reports the server's answer. */
    private void runPatch(FhirServerConfiguration target, ServerOrigin origin,
            PatchResourceDialog.Patch patch) {
        status.accept("Patching " + origin.resourceType() + "/" + origin.resourceId()
                + " on " + target.name() + " ...");
        document.busy(true);
        Thread worker = new Thread(() -> {
            ServerWriteResult written = null;
            Throwable failure = null;
            try {
                // The service takes the body before the format: the format travels as the
                // request's Content-Type, and the origin it patches is already fixed.
                written = serverService.patch(target, origin, patch.body(), patch.format());
            } catch (Throwable problem) {
                failure = problem;
            }
            ServerWriteResult result = written;
            Throwable error = failure;
            Platform.runLater(() -> {
                document.busy(false);
                onPatchFinished(target, origin, result, error);
            });
        }, "server-patch");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Reports a patch result.
     *
     * <p>The returned resource is re-read rather than shown from the patch response: a
     * server may answer a {@code PATCH} with {@code 200 OK} and an empty body, and showing
     * the local copy at that point would claim the server holds edits it may not have
     * applied. A reload is the only way to say what the server actually has.</p>
     */
    private void onPatchFinished(FhirServerConfiguration target, ServerOrigin origin,
            ServerWriteResult result, Throwable failure) {
        if (failure != null || result == null) {
            status.accept("Not patched: " + ServerErrors.describe(failure));
            return;
        }
        status.accept("Patched " + origin.resourceType() + "/" + origin.resourceId()
                + " on " + target.name() + "; reloading the server's version ...");
        reloadFromServer(target, origin);
    }

    /**
     * Deletes the displayed resource from the server it came from.
     *
     * <p>Confirms first, and the confirmation states the full type and id rather than
     * "this resource": a delete is not reversible from here, and the whole point of asking
     * is that the user can see what is about to go.</p>
     */
    void delete() {
        FhirServerConfiguration target = serverFor(document.origin());
        ServerOrigin origin = document.origin();
        if (target == null || origin == null) {
            status.accept("This resource did not come from a configured server.");
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle("Delete from FHIR Server");
        alert.setHeaderText("Delete " + origin.resourceType() + "/" + origin.resourceId()
                + " from " + target.name() + "?");
        alert.setContentText("Server: " + target.name() + "\n" + target.baseUrl()
                + "\n\nThis removes the resource on the server. It cannot be undone from here.");
        themeDialog(alert.getDialogPane());
        ButtonType proceed = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(proceed, ButtonType.CANCEL);
        if (alert.showAndWait().filter(proceed::equals).isEmpty()) {
            return;
        }
        status.accept("Deleting " + origin.resourceType() + "/" + origin.resourceId()
                + " from " + target.name() + " ...");
        document.busy(true);
        Thread worker = new Thread(() -> {
            Throwable failure = null;
            try {
                serverService.delete(target, origin);
            } catch (Throwable problem) {
                failure = problem;
            }
            Throwable error = failure;
            Platform.runLater(() -> {
                document.busy(false);
                if (error != null) {
                    status.accept("Not deleted: " + ServerErrors.describe(error));
                    return;
                }
                document.close();
                status.accept("Deleted " + origin.resourceType() + "/" + origin.resourceId()
                        + " from " + target.name() + ".");
            });
        }, "server-delete");
        worker.setDaemon(true);
        worker.start();
    }

    /** Gives a dialog the same stylesheets as the main window, so it follows the theme. */
    private void themeDialog(DialogPane pane) {
        pane.getStylesheets().addAll(themeManager.stylesheets());
        pane.setPrefWidth(620);
    }
}
