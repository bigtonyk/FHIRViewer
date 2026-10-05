package com.example.fhirviewer.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.server.PatchFormat;
import com.example.fhirviewer.server.ServerOrigin;

/**
 * Collects the two things a FHIR {@code PATCH} needs and nothing else: a format and a body.
 *
 * <p>Both are required, and both are chosen here rather than inferred. The format is carried
 * entirely by the {@code Content-Type}, so a JSON Patch sent as
 * {@code application/merge-patch+json} is a request that either mis-applies or is refused;
 * asking is the only safe way to get it right. That reasoning lives in {@link PatchFormat}
 * and is not repeated here.</p>
 *
 * <p>The body is typed into the same JSON/XML editor the main window already uses, rather
 * than in a second editor built for patches. The plan asks for exactly that, and there is a
 * practical reason beyond consistency: a merge patch that does not parse is a patch the
 * server will reject with a message about JSON, and a local parse catches it first.</p>
 */
public class PatchResourceDialog extends Dialog<PatchResourceDialog.Patch> {

    /**
     * A patch the user has written.
     *
     * @param format the patch format, which becomes the request's {@code Content-Type}
     * @param body   the patch document
     */
    public record Patch(PatchFormat format, String body) {
    }

    private final ChoiceBox<PatchFormat> formatBox = new ChoiceBox<>();
    private final JsonView bodyView = new JsonView();

    public PatchResourceDialog(ServerOrigin origin, ThemeManager themeManager) {
        setTitle("Patch on FHIR server");
        setHeaderText(origin == null
                ? "Apply a partial update to a resource on a FHIR server"
                : "Apply a partial update to " + origin.resourceType() + "/" + origin.resourceId());
        setResizable(true);

        formatBox.getItems().setAll(PatchFormat.values());
        formatBox.getSelectionModel().select(PatchFormat.JSON_MERGE_PATCH);
        formatBox.setTooltip(new Tooltip(
                "The format is sent as the Content-Type, so a JSON Patch must not be sent as a merge patch."));

        // The editor is the application's own: format, copy and the same styling. Its apply
        // handler is deliberately inert — the dialog's own Apply action runs the patch, and
        // letting this one fire would try to replace the resource being edited instead.
        bodyView.setOnApplyRequested(text -> { });
        bodyView.show("{\n  \n}");

        HBox formatRow = new HBox(8, new Label("Patch format"), formatBox);
        formatRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(formatBox, Priority.ALWAYS);

        ButtonType applyType = new ButtonType("Apply patch", ButtonBar.ButtonData.OK_DONE);
        VBox content = new VBox(10, formatRow, bodyView);
        content.setPadding(new Insets(12));
        VBox.setVgrow(bodyView, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        getDialogPane().setPrefWidth(720);
        getDialogPane().setPrefHeight(560);

        Button apply = (Button) getDialogPane().lookupButton(applyType);
        if (apply != null) {
            apply.getStyleClass().add("button-primary");
            apply.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                Patch patch = patch();
                if (patch == null) {
                    event.consume();
                    return;
                }
                setResult(patch);
                close();
            });
        }
    }

    /**
     * The patch the user wrote, or {@code null} when there is nothing to send.
     *
     * <p>An empty body is refused here rather than sent: an empty {@code PATCH} is a request
     * that either does nothing or is rejected, and neither is worth a round trip.</p>
     */
    private Patch patch() {
        PatchFormat format = formatBox.getValue();
        String body = bodyView.getText();
        if (body == null || body.isBlank()) {
            return null;
        }
        return new Patch(format, body);
    }
}
