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
import javafx.scene.control.ScrollPane;
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
    private final SplitPane split;

    /**
     * The default window size.
     *
     * <p>Wide on purpose. The two halves are two forms of fields side by side, and the
     * response side — status, four tabs, three buttons — is not usable when the request side
     * takes most of the width. 1400 comfortably fits both at a readable field width on a
     * 1080p screen, and is comfortably under the width of anything larger.
     */
    private static final double DEFAULT_WIDTH = 1400;
    private static final double DEFAULT_HEIGHT = 820;

    /**
     * The smallest the window may be dragged to.
     *
     * <p>Without a floor the dialog can be squeezed until neither pane is usable, and
     * {@link #remember()} would then persist that size and make it stick on every reopening.
     */
    private static final double MIN_WIDTH = 900;
    private static final double MIN_HEIGHT = 520;

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

        // The request side is much taller than the response side — a URL, three kinds of
        // credential, two grids and a body — so it scrolls rather than being squeezed. A
        // fixed-height VBox with no scroller silently squashes the two grids to nothing,
        // which is what made this screen look broken at a fixed size.
        ScrollPane requestScroller = new ScrollPane(requestPane);
        requestScroller.setFitToWidth(true);
        requestScroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        split = new SplitPane(requestScroller, responsePane);
        VBoxWithPadding content = new VBoxWithPadding(split, footer);
        getDialogPane().setContent(content);

        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(close);
        setResultConverter(button -> outcome());

        // Read once, then apply in one go: the remembered size and divider must come from
        // the same read as the form, or the window reopens at last run's size with this
        // run's divider.
        RestConsoleState remembered = RestConsoleState.load(baseUrlsOf(servers));
        applySize(remembered);
        requestPane.restoreState(remembered);

        // Save on every way out, including the "open in viewer" path — which closes this
        // window, and is the case that made remembering settings worth doing at all.
        setOnCloseRequest(event -> {
            remember();
            requestPane.forgetCredentials();
        });
    }

    /** Applies a remembered size and divider, rejecting values a resized window could produce. */
    private void applySize(RestConsoleState state) {
        double width = state.windowWidth() < MIN_WIDTH ? DEFAULT_WIDTH : state.windowWidth();
        double height = state.windowHeight() < MIN_HEIGHT ? DEFAULT_HEIGHT : state.windowHeight();
        getDialogPane().setPrefSize(width, height);
        if (split != null) {
            split.setDividerPositions(clampDivider(state.dividerPosition()));
        }

        // Clamped to the screen, and only once the window exists. Asking for 1400 on a
        // display whose *logical* width is 1280 — which is what 150% scaling on a 1920
        // screen gives — leaves the window manager to clamp it, and it clips the right-hand
        // edge. Widening the request makes that worse rather than better, which is exactly
        // what a wider dialog with a button missing reported.
        setOnShowing(event -> fitToScreen());

        // A minimum is set after the screen is known rather than here, because a fixed 900
        // is itself wider than a narrow screen and would defeat the clamp below.
    }

    /**
     * Fits the dialog inside the screen it is opening on.
     *
     * <p>Works on the {@link javafx.stage.Stage} rather than the {@code DialogPane}: the
     * pane's {@code setWidth} is protected, and it is the stage's size that the window
     * manager clamps anyway. Doing it in {@code setOnShowing} is what makes the stage
     * available at all.
     *
     * <p>Both the size and the floor are clamped. A floor larger than the screen would keep
     * the window from being shrunk back and reintroduce the clipping this removes.
     */
    private void fitToScreen() {
        javafx.stage.Window window = getDialogPane().getScene() == null
                ? null
                : getDialogPane().getScene().getWindow();
        if (window == null) {
            // No scene yet, which should not happen during onShowing, but a dialog that
            // cannot be sized is still a working dialog.
            return;
        }
        javafx.geometry.Rectangle2D bounds = screenBoundsOf(window);
        double availableWidth = Math.max(600, bounds.getWidth() - MARGIN);
        double availableHeight = Math.max(400, bounds.getHeight() - MARGIN);

        getDialogPane().setMinSize(Math.min(MIN_WIDTH, availableWidth),
                Math.min(MIN_HEIGHT, availableHeight));
        window.setWidth(fitWithin(window.getWidth(), MIN_WIDTH, availableWidth));
        window.setHeight(fitWithin(window.getHeight(), MIN_HEIGHT, availableHeight));
    }

    /**
     * Brings a requested size inside what the screen allows.
     *
     * <p>The window is never made smaller than {@code minimum} and never larger than
     * {@code available}. {@code available} wins when the two conflict, because a minimum
     * larger than the screen is what reintroduces the clipping this exists to remove.
     *
     * <p>Package-visible and free of JavaFX so it can be tested directly. That matters: the
     * alternative is a test that opens a window, which is either skipped headless or, worse,
     * passes without having exercised the arithmetic at all.
     */
    static double fitWithin(double requested, double minimum, double available) {
        return Math.min(Math.max(requested, minimum), available);
    }

    /**
     * The screen this dialog should fit inside.
     *
     * <p>{@code Screen.getScreensForRectangle} rather than anything on the window: {@code
     * Window} has no {@code getScreen()}, and asking which screens the window's own
     * rectangle touches is also the more accurate answer — it handles the window straddling
     * two monitors by picking the one it mostly sits on, and puts a dialog opened from a
     * second monitor on that monitor rather than the primary.
     */
    private static javafx.geometry.Rectangle2D screenBoundsOf(javafx.stage.Window window) {
        javafx.stage.Screen screen = null;
        if (window != null && window.getWidth() > 0 && window.getHeight() > 0) {
            java.util.List<javafx.stage.Screen> touching = javafx.stage.Screen
                    .getScreensForRectangle(window.getX(), window.getY(),
                            window.getWidth(), window.getHeight());
            // The first is the one the rectangle mostly covers, which is the one whose
            // bounds a dialog should fit inside.
            screen = touching.isEmpty() ? null : touching.get(0);
        }
        if (screen == null) {
            screen = javafx.stage.Screen.getPrimary();
        }
        return screen == null
                ? new javafx.geometry.Rectangle2D(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)
                : screen.getVisualBounds();
    }

    /** Space left around the window, so it does not sit flush against the screen edge. */
    private static final double MARGIN = 40;

    private static double clampDivider(double position) {
        // A divider dragged to the very edge leaves one pane unusable; keep both workable.
        return Math.min(0.85, Math.max(0.25, position));
    }

    private static List<String> baseUrlsOf(List<FhirServerConfiguration> servers) {
        if (servers == null) {
            return List.of();
        }
        return servers.stream().map(FhirServerConfiguration::baseUrl).toList();
    }

    /** Captures and stores the state, including the size the user dragged the window to. */
    private void remember() {
        double width = getDialogPane().getWidth();
        double height = getDialogPane().getHeight();
        double[] positions = split == null ? null : split.getDividerPositions();
        // Before the window is laid out there are no dividers yet, so there is no position to
        // remember; the default keeps the next opening sensible rather than storing a zero.
        double divider = positions == null || positions.length == 0
                ? RestConsoleState.defaults().dividerPosition()
                : positions[0];
        requestPane.captureState(width, height, divider).save();
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