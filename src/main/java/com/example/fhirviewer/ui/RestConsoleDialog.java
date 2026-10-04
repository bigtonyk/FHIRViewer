package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.ServerOperationException;
import com.example.fhirviewer.server.rest.JdkHttpRestClient;
import com.example.fhirviewer.server.rest.RestAnswer;
import com.example.fhirviewer.server.rest.RestAnswers;
import com.example.fhirviewer.server.rest.RestClient;
import com.example.fhirviewer.server.rest.RestRequest;
import com.example.fhirviewer.service.FhirService;

/**
 * The REST console: any REST call, with the answer shown raw and FHIR-aware.
 *
 * <p>This is the screen a Postman user opens when they want to talk to a FHIR server
 * directly, rather than through a curated list of operations. It bypasses the plugin layer
 * <em>on purpose</em> — calling an endpoint no plugin declares is the entire reason it
 * exists — and it is a peer of the plugin path rather than an exception to it. See
 * {@code docs/plans/11_REST_CONSOLE_UI.md}.
 *
 * <p><b>It returns a resource; it never opens one.</b> {@link Outcome} carries the resource
 * to {@code MainWindow}, exactly as {@link ServerOperationDialog} does, so there is still
 * one rendering path and the unsaved-changes guard is honoured in one place. The dialog
 * itself never touches the main window's nodes.
 *
 * <p><b>Nothing here knows what an endpoint means.</b> The form is
 * {@link RestConsoleForm}, the answer is classified by {@link RestAnswers}, and the panes
 * draw. A vendor endpoint this codebase has never heard of needs no code here.
 */
public class RestConsoleDialog extends Dialog<RestConsoleDialog.Outcome> {

    /**
     * What the caller gets when the user opens a FHIR answer in the viewer.
     *
     * @param resource the resource, or {@code null} when the answer held none
     * @param label    a short name including where it came from
     */
    public record Outcome(IBaseResource resource, String label) {

        /** True when there is something to display. */
        public boolean hasResource() {
            return resource != null;
        }
    }

    private final RestRequestPane requestPane;
    private final RestResponsePane responsePane;
    private final FhirService fhirService;

    private final Button sendButton = new Button("Send");
    private final Button cancelRequestButton = new Button("Cancel");
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label statusLabel = new Label(" ");

    private BackgroundTasks.Running running;
    private String requestLabel = "the request";

    /**
     * @param servers   configured servers to offer, or an empty list for a custom-URL console
     * @param preselect the server to start on, or {@code null} for a custom URL
     * @param fhirService used to list a Bundle's entries; may be {@code null}
     */
    public RestConsoleDialog(List<FhirServerConfiguration> servers,
            FhirServerConfiguration preselect, FhirService fhirService) {
        this.fhirService = fhirService;
        setTitle("REST Console");
        setResizable(true);

        responsePane = new RestResponsePane(fhirService, this::requestOpen);
        requestPane = new RestRequestPane(servers, preselect, ignored -> fetchToken());

        sendButton.setDefaultButton(true);
        sendButton.setOnAction(event -> send());
        cancelRequestButton.setDisable(true);
        cancelRequestButton.setOnAction(event -> cancelRunning());
        busy.setVisible(false);
        busy.setMaxSize(18, 18);
        statusLabel.getStyleClass().add("status-muted");
        statusLabel.setWrapText(true);

        HBox controls = new HBox(8, sendButton, cancelRequestButton, busy);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, controls, spacer, statusLabel);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        SplitPane split = new SplitPane(requestPane, responsePane);
        split.setDividerPositions(0.5);
        VBoxWithPadding content = new VBoxWithPadding(split, footer);
        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(1100);
        getDialogPane().setPrefHeight(720);

        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(close);
        setResultConverter(button -> outcome());

        // The credentials live only as long as this window does.
        setOnCloseRequest(event -> requestPane.forgetCredentials());
    }

    private static final class VBoxWithPadding extends javafx.scene.layout.VBox {

        VBoxWithPadding(javafx.scene.Node... children) {
            super(8, children);
            setPadding(new Insets(10));
        }
    }

    /** The panes, so a test can build and drive the console without a display manager. */
    public RestRequestPane requestPane() {
        return requestPane;
    }

    public RestResponsePane responsePane() {
        return responsePane;
    }

    /** What is still missing, for a test and for the Send button's own state. */
    public Optional<String> problem() {
        return requestPane.problem();
    }

/**
     * Validates the form, sends the request on a background thread, and shows the answer.
     *
     * <p>Every branch goes through {@link BackgroundTasks}, so the work is off the JavaFX
     * thread and the outcome comes back as an {@code Attempt} rather than an exception: a raw
 * * {@code HttpTimeoutException} escaping into an event handler would be logged by the
     * toolkit and shown to nobody.
 */
    private void send() {
        Optional<String> problem = requestPane.problem();
        if (problem.isPresent()) {
            // Named before the socket opens, which is the only version of this message that
            // can point at the field the user has to fix.
            setStatus("Enter " + problem.get() + " first.", true);
            return;
        }
        RestConsoleForm form = requestPane.form();
        RestRequest request = form.toRequest();
        requestLabel = form.method() + " " + request.path();

        cancelRunning();
        responsePane.clear();
        setBusy(true);
        setStatus("Sending " + requestLabel + " ...", false);

        running = BackgroundTasks.runAttempt("rest-console",
                () -> BackgroundTasks.attempt(() -> {
                    RestClient client = JdkHttpRestClient.forSession(form.toSession());
                    long started = System.currentTimeMillis();
                    try (RestClient closable = client) {
                        RestAnswer answer = RestAnswers.classify(closable.execute(request));
                        return new Completed(answer, System.currentTimeMillis() - started);
                    }
                }),
                attempt -> {
                    setBusy(false);
                    if (attempt.cancelled()) {
                        setStatus(requestLabel + " was cancelled.", false);
                    } else if (!attempt.succeeded()) {
                        setStatus(ServerErrors.redact(attempt.failure()), true);
                    } else {
                        Completed completed = attempt.value();
                        responsePane.show(completed.answer(), completed.elapsedMillis());
                        setStatus(RestAnswers.summary(completed.answer(), completed.elapsedMillis()),
                                !completed.answer().isSuccess());
                    }
                });
    }

    /** The classified answer and how long the exchange took. */
    private record Completed(RestAnswer answer, long elapsedMillis) {
    }

    /** Opens the resource the user chose, or asks the main window to. */
    private void requestOpen(IBaseResource resource) {
        if (resource == null) {
            return;
        }
        String id = resource.getIdElement() == null || !resource.getIdElement().hasIdPart()
                ? null
                : resource.getIdElement().getIdPart();
        pending = new Outcome(resource,
                resource.fhirType() + (id == null ? "" : "/" + id) + " (from " + requestLabel + ")");
        // Closing the dialog is what returns the Outcome; the main window does the rest.
        if (isShowing()) {
            close();
        }
    }

    private Outcome pending;

    /**
     * The value handed back to the caller.
     *
     * <p>{@code null} unless the user asked to open something, so a console that was only
     * used for its raw output closes without disturbing whatever is already in the viewer.
     */
    private Outcome outcome() {
        return pending;
    }

    /** Runs the OAuth client-credentials grant and puts the token in the field. */
    private void fetchToken() {
        new TokenFetchDialog(getOwner(), this).show();
    }

    /** Called by {@link TokenFetchDialog} once a grant has been obtained. */
    void adoptFetchedToken(String token, String description) {
        requestPane.adoptToken(token);
        setStatus(description, true);
    }

    private void setBusy(boolean working) {
        busy.setVisible(working);
        sendButton.setDisable(working);
        cancelRequestButton.setDisable(!working);
    }

    private void setStatus(String message, boolean error) {
        statusLabel.getStyleClass().remove("status-error");
        if (error) {
            statusLabel.getStyleClass().add("status-error");
        }
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
    }

    /** Stops the request in flight, if any. */
    private void cancelRunning() {
        if (running != null) {
            running.cancel();
            running = null;
        }
    }
}