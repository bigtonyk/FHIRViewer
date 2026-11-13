package com.spiraleyes.fhirviewer.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;

/**
 * What an authorized session was used to read: how far the launch got toward an actual
 * FHIR request, and what came back.
 *
 * <p>Replaces the Phase 01 Resource access placeholder. One live button performs the
 * Phase 04 minimal operation — reading the patient context the token granted — and a
 * second opens the fetched resource in the existing viewer through the dialog's result,
 * so this pane never needs its own tree or Pretty View. Both stay disabled until there
 * is a session that can authorize; the status line says why at every step rather than
 * the workbench implying a read happened.</p>
 *
 * <p><b>An absent patient context is spelled out.</b> A button that is grey for no
 * stated reason reads as broken, while a successful SMART launch with no patient is a
 * legitimate outcome — identity-scoped launches carry no patient at all. When the
 * session has no patient the pane says so outright and keeps the read button off, which
 * is the only honest way to present a launch that worked and still has nothing to
 * read.</p>
 */
final class SmartResourcePane extends VBox {

    private final Label stateValue = paneLabel();
    private final Label resultValue = paneLabel();
    private final Button readButton = new Button("Read patient context");
    private final Button openButton = new Button("Open in viewer");

    SmartResourcePane() {
        setPadding(new Insets(0));
        setSpacing(10);
        readButton.setMaxWidth(Double.MAX_VALUE);
        openButton.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(stateValue, readButton, resultValue, openButton);
        showNothingYet();
    }

    /** No session exists yet; nothing can be read and nothing pretends otherwise. */
    void showNothingYet() {
        stateValue.setText("No session exists yet. Authorize first: a resource "
                + "can only be read through an authorized session.");
        readButton.setDisable(true);
        readButton.setText("Read patient context");
        resultValue.setText("");
        resultValue.setVisible(false);
        resultValue.setManaged(false);
        resultValue.getStyleClass().remove("status-error");
        openButton.setDisable(true);
        openButton.setVisible(false);
        openButton.setManaged(false);
    }

    /** A session exists; the read button follows whether it granted a patient. */
    void show(SmartAuthorizationSession session) {
        if (session == null || session.isLoggedOut()) {
            showNothingYet();
            return;
        }
        if (session.isExpired()) {
            stateValue.setText("The access token has expired. Authorize again "
                    + "before reading.");
            readButton.setDisable(true);
            return;
        }
        String patient = session.patient();
        if (patient == null || patient.isBlank()) {
            stateValue.setText("Authorized, but this launch granted no patient context. "
                    + "That is a normal successful outcome — identity-scoped launches "
                    + "carry no patient — so there is nothing to read here.");
            readButton.setDisable(true);
            return;
        }
        stateValue.setText("Authorized. The launch granted patient context "
                + patient + ": read it to open it in the viewer.");
        readButton.setDisable(false);
        resultValue.setText("");
        resultValue.setVisible(false);
        resultValue.setManaged(false);
        openButton.setDisable(true);
        openButton.setVisible(false);
        openButton.setManaged(false);
    }

    /** While a read is in flight the button says so and cannot be pressed twice. */
    void setReading(boolean busy) {
        readButton.setDisable(busy);
        readButton.setText(busy ? "Reading ..." : "Read patient context");
        if (!busy) {
            return;
        }
        resultValue.setText("");
        resultValue.setVisible(false);
        resultValue.setManaged(false);
        resultValue.getStyleClass().remove("status-error");
        openButton.setDisable(true);
        openButton.setVisible(false);
        openButton.setManaged(false);
    }

    /** A read succeeded: describe the resource and offer to open it. */
    void showResource(String description) {
        resultValue.getStyleClass().remove("status-error");
        resultValue.setText("Read " + description
                + ". Choose Open in viewer to display it with the existing tree and "
                + "Pretty View.");
        resultValue.setVisible(true);
        resultValue.setManaged(true);
        openButton.setDisable(false);
        openButton.setVisible(true);
        openButton.setManaged(true);
    }

    /** A read failed: the redacted reason, with no resource offered. */
    void showFailure(String message) {
        if (!resultValue.getStyleClass().contains("status-error")) {
            resultValue.getStyleClass().add("status-error");
        }
        resultValue.setText(message == null || message.isBlank()
                ? "The read failed for an unknown reason."
                : message);
        resultValue.setVisible(true);
        resultValue.setManaged(true);
        openButton.setDisable(true);
        openButton.setVisible(false);
        openButton.setManaged(false);
    }

    /** Everything on display, for a test to assert against. */
    String displayText() {
        StringBuilder all = new StringBuilder();
        for (javafx.scene.Node node : getChildrenUnmodifiable()) {
            if (node instanceof javafx.scene.control.Labeled labeled
                    && labeled.getText() != null && labeled.isVisible()) {
                all.append(labeled.getText()).append('\n');
            }
        }
        return all.toString();
    }

    /** Reads the granted patient context through the authorized session. */
    Button readButton() {
        return readButton;
    }

    /** Opens the last read resource in the existing viewer. */
    Button openButton() {
        return openButton;
    }

    private static Label paneLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add("app-subtitle");
        return label;
    }
}
