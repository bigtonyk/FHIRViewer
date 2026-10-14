package com.spiraleyes.fhirviewer.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.spiraleyes.fhirviewer.server.smart.SmartConfiguration;

/**
 * What discovery learned about a server: its advertised endpoints, capabilities, scopes and
 * PKCE methods — and anything wrong with them.
 *
 * <p>The Phase 01 placeholder said what would appear here; this is what appears. The layout
 * follows {@code ServerFormPanel}: a pinned label column and a growing value column, with
 * long URLs wrapping rather than scrolling sideways. Optional fields the server did not
 * publish take their whole row with them, so a sparse document reads as sparse rather than
 * as a column of blanks.</p>
 *
 * <p><b>Problems are shown beside the metadata, not instead of it.</b> A document with a
 * malformed endpoint still tells the user which authorization server they were pointed at,
 * which is exactly the information they need to file the bug. Problems are listed under the
 * fields, and the launch-ability verdict is a separate line so "here is what it said" and
 * "here is whether we will use it" never blur together.</p>
 *
 * <p>Nothing displayed here could be a credential: a discovery document is public by
 * definition, and no token exists yet in any state this pane can be shown in.</p>
 */
final class SmartCapabilitiesPane extends VBox {

    private final Label issuerValue = valueLabel();
    private final Label authorizationValue = valueLabel();
    private final Label tokenValue = valueLabel();
    private final Label jwksValue = valueLabel();
    private final Label capabilitiesValue = valueLabel();
    private final Label scopesValue = valueLabel();
    private final Label pkceValue = valueLabel();
    private final Label grantTypesValue = valueLabel();
    private final Label unknownValue = valueLabel();

    private final Label problemsValue = valueLabel();
    private final Label verdictValue = valueLabel();

    /** Title and value of one optional row, so both can be shown or hidden together. */
    private record Row(Label title, Label value) {
    }

    private final List<Row> rows = new java.util.ArrayList<>();
    private final GridPane grid;

    SmartCapabilitiesPane() {
        setPadding(new Insets(0));

        grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        // The same pinned-label / growing-value shape as ServerFormPanel, so both halves of
        // the workbench resize together.
        ColumnConstraints labelColumn = new ColumnConstraints(120);
        labelColumn.setHgrow(Priority.NEVER);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);

        int row = 0;
        row = addRow(grid, row, "Issuer", issuerValue);
        row = addRow(grid, row, "Authorization", authorizationValue);
        row = addRow(grid, row, "Token endpoint", tokenValue);
        row = addRow(grid, row, "JWKS", jwksValue);
        row = addRow(grid, row, "Capabilities", capabilitiesValue);
        row = addRow(grid, row, "Scopes", scopesValue);
        row = addRow(grid, row, "PKCE methods", pkceValue);
        row = addRow(grid, row, "Grant types", grantTypesValue);
        row = addRow(grid, row, "Other fields", unknownValue);

        problemsValue.getStyleClass().add("status-error");
        verdictValue.getStyleClass().add("app-subtitle");
        GridPane.setColumnSpan(problemsValue, 2);
        GridPane.setColumnSpan(verdictValue, 2);
        grid.add(problemsValue, 0, row++);
        grid.add(verdictValue, 0, row);

        getChildren().add(grid);
        showNothingYet();
    }
    /** Back to the empty state: nothing has been discovered. */
    void showNothingYet() {
        hideRow(issuerValue);
        hideRow(authorizationValue);
        hideRow(tokenValue);
        hideRow(jwksValue);
        hideRow(capabilitiesValue);
        hideRow(scopesValue);
        hideRow(pkceValue);
        hideRow(grantTypesValue);
        hideRow(unknownValue);
        problemsValue.getStyleClass().add("status-error");
        problemsValue.setText("Nothing has been discovered yet. Choose Discover to read "
                + "this server's SMART configuration document.");
        verdictValue.setText("");
    }

    /** Shows a discovery attempt that failed, with the reason the user can act on. */
    void showFailure(String message) {
        showNothingYet();
        problemsValue.setText(message);
    }

    /**
     * Shows a document the server did publish, with whatever is wrong with it.
     *
     * <p>Each optional row is hidden when the server published nothing for it, so a minimal
     * document does not read as a screen of empty labels.</p>
     */
    void show(SmartConfiguration configuration) {
        showNothingYet();

        setRow(issuerValue, configuration.issuer());
        setRow(authorizationValue, configuration.authorizationEndpoint());
        setRow(tokenValue, configuration.tokenEndpoint());
        setRow(jwksValue, configuration.jwksUri());
        setRow(capabilitiesValue, join(configuration.capabilities()));
        setRow(scopesValue, join(configuration.scopesSupported()));
        setRow(pkceValue, join(configuration.codeChallengeMethodsSupported()));
        setRow(grantTypesValue, join(configuration.grantTypesSupported()));
        setRow(unknownValue, join(configuration.unknownFields().keySet()));

        List<String> problems = configuration.problems();
        if (problems.isEmpty()) {
            problemsValue.getStyleClass().remove("status-error");
            problemsValue.setText("No problems found in the document.");
            verdictValue.setText(configuration.isUsable()
                    ? "This server can be launched against."
                    : "");
        } else {
            if (!problemsValue.getStyleClass().contains("status-error")) {
                problemsValue.getStyleClass().add("status-error");
            }
            problemsValue.setText(String.join("\n", problems));
            verdictValue.setText(configuration.isUsable()
                    ? "The launch endpoints look usable, but the document has the problems above."
                    : "This server cannot be launched against until the problems above are fixed.");
        }
    }

    /**
     * Everything currently on display, for a test to assert against.
     *
     * <p>Read from the labels rather than the inputs, so a test sees what a user would see
     * rather than what the pane was told.</p>
     */
    String displayText() {
        StringBuilder all = new StringBuilder();
        for (javafx.scene.Node node : grid.getChildrenUnmodifiable()) {
            if (node instanceof Label label && label.getText() != null) {
                all.append(label.getText()).append('\n');
            }
        }
        return all.toString();
    }

    /** A value label in the house style: wraps, and grows with its column. */
    private static Label valueLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add("app-subtitle");
        return label;
    }

    /** Adds one title/value row, remembers the pair, and returns the next row number. */
    private int addRow(GridPane grid, int row, String title, Label value) {
        Label label = new Label(title);
        label.getStyleClass().add("pretty-row-label");
        grid.add(label, 0, row);
        grid.add(value, 1, row);
        GridPane.setHgrow(value, Priority.ALWAYS);
        rows.add(new Row(label, value));
        return row + 1;
    }

    /** Fills a row, or hides it entirely when the server published nothing for it. */
    private void setRow(Label value, String text) {
        if (text == null || text.isBlank()) {
            hideRow(value);
            return;
        }
        value.setText(text);
        setRowVisible(value, true);
    }

    private void hideRow(Label value) {
        value.setText("");
        setRowVisible(value, false);
    }

    /**
     * Shows or hides a value label and the title beside it.
     *
     * <p>Both halves, because a hidden value with its title still on screen reads as a
     * field the server left empty rather than one it does not have — the exact mistake the
     * credential rows of {@code ServerFormPanel} record in their own javadoc.</p>
     */
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

    /** Joins a collection for one line, or reports that there was nothing to join. */
    private static String join(java.util.Collection<String> values) {
        return values == null || values.isEmpty() ? null : String.join("  ", values);
    }
}