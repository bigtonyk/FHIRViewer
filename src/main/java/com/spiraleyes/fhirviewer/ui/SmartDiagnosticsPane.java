package com.spiraleyes.fhirviewer.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.spiraleyes.fhirviewer.server.smart.SmartDiagnostics;
import com.spiraleyes.fhirviewer.server.smart.SmartDiagnosticsEvent;

/**
 * The Diagnostics tab of the SMART Launch Workbench: a structured, redacted
 * event list for discovery, authorization, token exchange, session and FHIR
 * access, plus export and clear.
 *
 * <p>Every event shown has already been redacted by {@link SmartDiagnostics},
 * so nothing here can leak a token, code, verifier, secret, authorization
 * header, or patient identifier.</p>
 */
final class SmartDiagnosticsPane extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final SmartDiagnostics diagnostics;
    private final ListView<SmartDiagnosticsEvent> list = new ListView<>();
    private final Label status = new Label();

    SmartDiagnosticsPane(SmartDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        setPadding(new Insets(0));
        setSpacing(10);

        Label title = new Label("Diagnostic events");
        title.getStyleClass().add("pretty-row-label");

        list.setCellFactory(lv -> new EventCell());
        list.setPrefHeight(260);

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button exportButton = new Button("Export diagnostics...");
        exportButton.setMaxWidth(Double.MAX_VALUE);
        exportButton.setOnAction(event -> onExport());

        Button clearButton = new Button("Clear diagnostics");
        clearButton.setMaxWidth(Double.MAX_VALUE);
        clearButton.setOnAction(event -> onClear());

        HBox buttons = new HBox(8, exportButton, clearButton);
        buttons.setMaxWidth(Double.MAX_VALUE);

        status.getStyleClass().add("app-subtitle");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(title, scroll, buttons, status);
        refresh();
    }

    /** Writes the redacted diagnostic log to a file in the user's app directory. */
    private void onExport() {
        String text = diagnostics.export();
        Path dir = Path.of(System.getProperty("user.home", "."), ".fhirviewer");
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("smart-diagnostics-"
                    + Instant.now().toString().replace(':', '-') + ".txt");
            Files.writeString(file, text);
            status.setText("Diagnostics exported to " + file);
        } catch (IOException e) {
            status.setText("Could not export diagnostics: "
                    + ServerErrors.redact(e.getMessage()));
        }
    }

    /** Clears every diagnostic event from the current session. */
    private void onClear() {
        diagnostics.clear();
        refresh();
        status.setText("Diagnostics cleared.");
    }

    private void refresh() {
        list.getItems().setAll(diagnostics.events());
        int count = diagnostics.events().size();
        status.setText(count + " event" + (count == 1 ? "" : "s") + " recorded.");
    }

    private static final class EventCell extends ListCell<SmartDiagnosticsEvent> {
        @Override
        protected void updateItem(SmartDiagnosticsEvent item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(TIME.format(item.instant()) + "  [" + item.phase() + "/" + item.kind()
                    + "]  " + item.message()
                    + (item.detail() != null && !item.detail().isBlank()
                            ? "  (" + item.detail() + ")" : ""));
        }
    }
}
