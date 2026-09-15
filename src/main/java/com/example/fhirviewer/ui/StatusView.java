package com.example.fhirviewer.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;

/**
 * Status bar with the current message plus the list of validation messages.
 */
public class StatusView extends VBox {

    private static final String READY_MESSAGE = "Ready. Use File > Open to load a FHIR JSON or XML resource.";

    private final Label statusLabel = new Label(READY_MESSAGE);
    private final ListView<ValidationIssue> issueList = new ListView<>();
    private final TitledPane issuesPane = new TitledPane("Validation messages", issueList);

    public StatusView() {
        getStyleClass().add("status-bar");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setPadding(new Insets(6, 8, 6, 8));

        issueList.setCellFactory(view -> new IssueCell());
        issueList.setPrefHeight(120);
        VBox.setVgrow(issueList, Priority.ALWAYS);

        issuesPane.setExpanded(false);
        issuesPane.setCollapsible(true);

        getChildren().addAll(statusLabel, issuesPane);
    }

    /** Sets the status message shown in the status bar. */
    public void setStatus(String message) {
        statusLabel.setText(message == null || message.isBlank() ? READY_MESSAGE : message);
    }

    /** Displays the messages of a validation report. */
    public void showValidation(ValidationReport report) {
        issueList.getItems().setAll(report.getIssues());
        issuesPane.setText("Validation messages (" + report.getIssues().size() + ")");
        issuesPane.setExpanded(report.hasIssues());
        setStatus(report.getSummary());
    }

    /** Removes the currently displayed validation messages. */
    public void clearValidation() {
        issueList.getItems().clear();
        issuesPane.setText("Validation messages");
        issuesPane.setExpanded(false);
    }

    /** List cell that renders a validation message, including its severity. */
    private static final class IssueCell extends ListCell<ValidationIssue> {

        @Override
        protected void updateItem(ValidationIssue item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.getDisplayText());
            setWrapText(true);
            getStyleClass().removeAll("status-error", "status-warning");
            switch (item.severity()) {
                case FATAL, ERROR -> getStyleClass().add("status-error");
                case WARNING -> getStyleClass().add("status-warning");
                case INFORMATION -> { }
            }
        }
    }
}