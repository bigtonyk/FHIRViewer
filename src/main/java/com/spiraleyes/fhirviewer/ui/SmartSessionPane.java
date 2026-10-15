package com.spiraleyes.fhirviewer.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;

/**
 * What authorization produced: granted scopes, patient and encounter
 * context, user identity, and token expiry.
 *
 * <p>Replaces the Phase 01 Session placeholder. Rows hide when the server
 * published nothing for them — patient context may legitimately be absent
 * even when authorization succeeds. The raw access token is never shown:
 * the panel reports that a token exists and when it expires.</p>
 */
final class SmartSessionPane extends VBox {

    private final Label stateValue = valueLabel();
    private final Label scopesValue = valueLabel();
    private final Label patientValue = valueLabel();
    private final Label encounterValue = valueLabel();
    private final Label subjectValue = valueLabel();
    private final Label expiryValue = valueLabel();

    private record Row(Label title, Label value) {
    }

    private final java.util.List<Row> rows = new java.util.ArrayList<>();
    private final GridPane grid;

    SmartSessionPane() {
        setPadding(new Insets(0));
        grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labelColumn = new ColumnConstraints(120);
        labelColumn.setHgrow(Priority.NEVER);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);

        int row = 0;
        row = addRow(grid, row, "State", stateValue);
        row = addRow(grid, row, "Granted scopes", scopesValue);
        row = addRow(grid, row, "Patient", patientValue);
        row = addRow(grid, row, "Encounter", encounterValue);
        row = addRow(grid, row, "User", subjectValue);
        row = addRow(grid, row, "Token expiry", expiryValue);
        getChildren().add(grid);
        showNothingYet();
    }

    /** No session exists yet; every value says what will appear there. */
    void showNothingYet() {
        stateValue.setText("No session exists yet.");
        setRowVisible(stateValue, true);
        hide(scopesValue, patientValue, encounterValue, subjectValue, expiryValue);
    }

    /** Fills the panel from a live session; never shows the token itself. */
    void show(SmartAuthorizationSession session) {
        if (session == null || session.isLoggedOut()) {
            stateValue.setText(session == null ? "No session exists yet."
                    : "The session was cleared (logout). Authorize again for a new one.");
            setRowVisible(stateValue, true);
            hide(scopesValue, patientValue, encounterValue, subjectValue, expiryValue);
            return;
        }
        stateValue.setText(session.isExpired()
                ? "Authorization expired. Authorize again for a new session."
                : "Authorized. The access token is held in memory and never shown here.");
        setRowVisible(stateValue, true);
        setRow(scopesValue, grantedText(session));
        setRow(patientValue, session.patient());
        setRow(encounterValue, session.encounter());
        setRow(subjectValue, session.subject().orElse(null));
        setRow(expiryValue, session.expiresAt().map(Object::toString)
                .orElse("The server did not state a lifetime."));
    }

    private static String grantedText(SmartAuthorizationSession session) {
        String granted = session.grantedScope();
        if (granted == null || granted.isBlank()) {
            return null;
        }
        return granted;
    }

    /** Everything on display, for a test to assert against. */
    String displayText() {
        StringBuilder all = new StringBuilder();
        for (javafx.scene.Node node : grid.getChildrenUnmodifiable()) {
            if (node instanceof Label label && label.getText() != null && label.isVisible()) {
                all.append(label.getText()).append('\n');
            }
        }
        return all.toString();
    }

    private static Label valueLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add("app-subtitle");
        return label;
    }

    private int addRow(GridPane grid, int row, String title, Label value) {
        Label label = new Label(title);
        label.getStyleClass().add("pretty-row-label");
        grid.add(label, 0, row);
        grid.add(value, 1, row);
        GridPane.setHgrow(value, Priority.ALWAYS);
        rows.add(new Row(label, value));
        return row + 1;
    }

    private void setRow(Label value, String text) {
        if (text == null || text.isBlank()) {
            value.setText("");
            setRowVisible(value, false);
            return;
        }
        value.setText(text);
        setRowVisible(value, true);
    }

    private void hide(Label... values) {
        for (Label value : values) {
            value.setText("");
            setRowVisible(value, false);
        }
    }

    private void setRowVisible(Label value, boolean visible) {
        value.setVisible(visible);
        value.setManaged(visible);
        for (Row row : rows) {
            if (row.value() == value) {
                row.title().setVisible(visible);
                row.title().setManaged(visible);
            }
        }
    }
}

