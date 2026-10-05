package com.example.fhirviewer.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.BundleEntryInfo;
import com.example.fhirviewer.server.rest.RestAnswer;
import com.example.fhirviewer.server.rest.RestAnswers;
import com.example.fhirviewer.service.FhirService;

/**
 * What came back: the raw body, the headers, the diagnostics, and the way out.
 *
 * <p><b>The raw body is always here, even when a resource was parsed out of it.</b> Those
 * are two different jobs and the screen is better at each with its own pane: a user
 * debugging a REST call needs the bytes the server sent, and a user looking at a Patient
 * needs the tree. Showing the parsed resource <em>instead of</em> the body would make the
 * console worse at the thing it is for.
 *
 * <p><b>Header values are redacted.</b> They come from
 * {@link com.example.fhirviewer.server.rest.RestHeaders#toString()}, which replaces every
 * credential-bearing value. A response can carry a {@code Set-Cookie}, and this pane is the
 * one place it would otherwise end up on screen.
 *
 * <p><b>A Bundle gets an entry list.</b> A FHIR search answers with a Bundle, and what a
 * user wants from a search is usually one of the results rather than the wrapper. That is
 * the most FHIR-specific thing on this screen, and it is why the pane can open a resource
 * rather than only describe one.
 */
public final class RestResponsePane extends VBox {

    private final Label summaryLabel = new Label(" ");
    private final TextArea bodyArea = new TextArea();
    private final TextArea headersArea = new TextArea();
    private final TextArea diagnosticsArea = new TextArea();
    private final ListView<String> entryList = new ListView<>();
    private final Button openButton = new Button("Open in FHIR Viewer");
    private final Button saveButton = new Button("Save body to file…");
    private final Button copyButton = new Button("Copy body");

    private final FhirService fhirService;
    private final Consumer<IBaseResource> onOpen;

    private RestAnswer lastAnswer;
    private List<BundleEntryInfo> lastEntries = List.of();

    /**
     * @param fhirService used to expand a Bundle's entries; {@code null} disables the entry
     *                    list, which is what a caller without one wants
     * @param onOpen     called with the resource the user chose to open
     */
    public RestResponsePane(FhirService fhirService, Consumer<IBaseResource> onOpen) {
        super(8);
        this.fhirService = fhirService;
        this.onOpen = onOpen;
        setPadding(new Insets(12));
        getStyleClass().add("rest-response-pane");

        for (TextArea area : List.of(bodyArea, headersArea, diagnosticsArea)) {
            area.setEditable(false);
            area.setWrapText(false);
            VBox.setVgrow(area, Priority.ALWAYS);
        }
        VBox.setVgrow(entryList, Priority.ALWAYS);

        summaryLabel.getStyleClass().add("status-muted");
        summaryLabel.setWrapText(true);

        openButton.setOnAction(event -> openSelected());
        copyButton.setOnAction(event -> copyBody());
        saveButton.setOnAction(event -> saveBody());

        entryList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedEntry();
            }
        });

        TabPane tabs = new TabPane(
                new Tab("Body", bodyArea),
                new Tab("Bundle entries", entryList),
                new Tab("Headers", headersArea),
                new Tab("Diagnostics", diagnosticsArea));
        tabs.getTabs().forEach(tab -> tab.setClosable(false));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // A FlowPane, not an HBox, and that is the whole fix for a button being cut off. An
        // HBox lays its children out at their preferred widths and clips whatever does not
        // fit, so the last button simply disappears on a narrow window — and no amount of
        // making the dialog wider fixes it, because the buttons wrap nowhere. A FlowPane
        // moves them onto a second line instead.
        getChildren().addAll(summaryLabel, tabs, buttonRow());
        clear();
    }

    /** The buttons, on a pane that wraps them rather than clipping the last one. */
    private FlowPane buttonRow() {
        FlowPane row = new FlowPane(8, 8, openButton, copyButton, saveButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("rest-button-row");
        return row;
    }

    /** The widest the row would like to be, so a test can prove it is not being clipped. */
    double buttonRowPreferredWidth() {
        double widest = 0;
        for (javafx.scene.Node child : buttonRow().getChildren()) {
            double childWidth = child.prefWidth(-1);
            if (childWidth > widest) {
                widest = childWidth;
            }
        }
        return widest;
    }

/**
     * Shows an answer, replacing whatever was on screen.
     *
     * <p>Called on the JavaFX thread from the background task's completion handler, which
     * is what makes touching these nodes here safe.
     */
    public void show(RestAnswer answer, long elapsedMillis) {
        this.lastAnswer = answer;
        bodyArea.setText(answer == null ? "" : answer.bodyOrEmpty());
        headersArea.setText(answer == null ? "" : answer.headers().toString());
        diagnosticsArea.setText(answer == null ? ""
                : String.join("\n", RestAnswers.diagnostics(answer)));
        summaryLabel.setText(RestAnswers.summary(answer, elapsedMillis));
        summaryLabel.getStyleClass().remove("status-error");
        if (answer != null && !answer.isSuccess()) {
            summaryLabel.getStyleClass().add("status-error");
        }
        boolean hasBody = answer != null && !answer.bodyOrEmpty().isEmpty();
        copyButton.setDisable(!hasBody);
        saveButton.setDisable(!hasBody);

        IBaseResource resource = answer == null ? null : answer.resource();
        openButton.setDisable(resource == null);
        showEntries(answer, resource);
    }

    /** Empties the pane, so a stale answer is never read as the current one. */
    public void clear() {
        lastAnswer = null;
        lastEntries = List.of();
        bodyArea.clear();
        headersArea.clear();
        diagnosticsArea.clear();
        summaryLabel.setText(" ");
        summaryLabel.getStyleClass().remove("status-error");
        openButton.setDisable(true);
        copyButton.setDisable(true);
        saveButton.setDisable(true);
        entryList.getItems().clear();
        entryList.setVisible(false);
        entryList.setManaged(false);
    }

    /** The resource the Open button would hand back, or {@code null}. */
    public IBaseResource openableResource() {
        return lastAnswer == null ? null : lastAnswer.resource();
    }

    private void showEntries(RestAnswer answer, IBaseResource resource) {
        entryList.getItems().clear();
        lastEntries = List.of();
        if (answer == null || answer.kind() != RestAnswer.Kind.BUNDLE
                || fhirService == null || resource == null) {
            entryList.setVisible(false);
            entryList.setManaged(false);
            return;
        }
        lastEntries = fhirService.bundleEntries(resource);
        for (BundleEntryInfo entry : lastEntries) {
            entryList.getItems().add(entry.hasResource()
                    ? entry.displayName()
                    : entry.displayName() + " (no resource)");
        }
        boolean any = !lastEntries.isEmpty();
        entryList.setVisible(any);
        entryList.setManaged(any);
    }

    private void openSelected() {
        IBaseResource resource = openableResource();
        if (resource != null && onOpen != null) {
            onOpen.accept(resource);
        }
    }

    private void openSelectedEntry() {
        int index = entryList.getSelectionModel().getSelectedIndex();
        if (index >= 0 && index < lastEntries.size() && onOpen != null) {
            IBaseResource resource = lastEntries.get(index).resource();
            if (resource != null) {
                onOpen.accept(resource);
            }
        }
    }

    private void copyBody() {
        try {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(bodyArea.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        } catch (IllegalStateException noClipboard) {
            // Nothing to do but leave the text where it is; the user can select it.
        }
    }

/**
     * Writes the raw body to a file the user chooses.
     *
     * <p>Worth having on its own: the answer from a vendor endpoint is often the only record
     * of what the server actually said, and the user needs it in a file to attach to a
     * ticket.
     */
    private void saveBody() {
        String body = bodyArea.getText();
        if (body.isBlank()) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save response body");
        chooser.setInitialFileName("response" + extensionFor(lastAnswer));
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        java.io.File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        try {
            com.example.fhirviewer.util.FileSupport.writeText(target.toPath(), body);
            summaryLabel.setText("Saved the response body to " + target.getName() + ".");
        } catch (java.io.IOException e) {
            summaryLabel.setText("The body could not be saved: " + e.getMessage());
            summaryLabel.getStyleClass().add("status-error");
        }
    }

    /** A file extension matching what came back, so the file opens in something sensible. */
    private static String extensionFor(RestAnswer answer) {
        if (answer == null) {
            return ".txt";
        }
        return switch (answer.kind()) {
            case JSON, FHIR_RESOURCE, BUNDLE, OPERATION_OUTCOME -> ".json";
            case XML -> ".xml";
            default -> ".txt";
        };
    }

    /** The summary line, so a test can assert what the user was told. */
    public String summary() {
        return summaryLabel.getText();
    }

    /** How many Bundle entries are listed; zero when the answer was not a Bundle. */
    public int entryCount() {
        return entryList.getItems().size();
    }

    /** The listed entry names, for a test that checks a Bundle was understood. */
    public List<String> entryNames() {
        return List.copyOf(entryList.getItems());
    }

    /** True when the Open button would do something, i.e. the answer held a resource. */
    public boolean canOpen() {
        return !openButton.isDisable();
    }

    /** The Open button, so a test can drive it and measure where it landed. */
    public Button openButton() {
        return openButton;
    }

    /** The copy button, for the same reason as {@link #openButton()}. */
    public Button copyButton() {
        return copyButton;
    }

    /** The save button, for the same reason as {@link #openButton()}. */
    public Button saveButton() {
        return saveButton;
    }
}