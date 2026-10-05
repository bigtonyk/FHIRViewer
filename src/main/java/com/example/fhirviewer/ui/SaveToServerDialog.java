package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.ServerOrigin;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Confirms a write to a FHIR server before it happens.
 *
 * <p>Everything the user needs to make the decision is on one screen: which server, and
 * what the write will actually do to it. The verb - create or overwrite - is spelled out
 * because the two are not the same risk, and the version situation is stated plainly,
 * because a resource the server gave no version for cannot be checked for a conflicting
 * change and implying otherwise would be a lie told for convenience.</p>
 *
 * <p>The checkboxes are never pre-ticked. Overwriting somebody else's edit to a clinical
 * record is a decision a person has to make, not a default this screen is allowed to
 * pick, so a force write is something the user asks for out loud.</p>
 *
 * <p>The dialog decides nothing. It returns a {@link Plan} that
 * {@link ServerResourceCoordinator} carries out, so the rules stay testable without a
 * toolkit and this class stays layout plus handler wiring.</p>
 */
public class SaveToServerDialog extends Dialog<SaveToServerDialog.Plan> {

    /**
     * What the user chose.
     *
     * @param server the server to write to
     * @param force  {@code true} when the write must go through even though the server
     *               copy has changed
     */
    public record Plan(FhirServerConfiguration server, boolean force) {

        public Plan {
            Objects.requireNonNull(server, "server");
        }
    }

    private final ComboBox<FhirServerConfiguration> serverBox = new ComboBox<>();
    private final Label summary = new Label();
    private final CheckBox force = new CheckBox(
            "Overwrite the server's copy even though it changed since you loaded it");
    private final CheckBox unversioned = new CheckBox(
            "Send without a version check (this server reports no versions)");

    private final ServerOrigin origin;
    private final IBaseResource resource;
    private final String typeName;

    /**
     * @param servers  the configured servers; the user picks one
     * @param preselect the server the resource came from, or {@code null} to start on
     *                 the first configured server
     * @param origin   where the resource came from, or {@code null} when it has never
     *                 been on a server
     * @param resource the resource about to be written
     * @param typeName the type as it will be addressed, used when the resource has no
     *                 origin to read it from
     */
    public SaveToServerDialog(List<FhirServerConfiguration> servers, FhirServerConfiguration preselect,
            ServerOrigin origin, IBaseResource resource, String typeName) {
        Objects.requireNonNull(servers, "servers");
        this.origin = origin;
        this.resource = resource;
        this.typeName = typeName;

        setTitle("Save to FHIR Server");
        setResizable(true);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        setResultConverter(button -> saveType.equals(button) ? plan() : null);

        serverBox.getItems().setAll(servers);
        if (preselect != null && serverBox.getItems().contains(preselect)) {
            serverBox.setValue(preselect);
        } else if (!serverBox.getItems().isEmpty()) {
            serverBox.setValue(serverBox.getItems().get(0));
        }
        serverBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(FhirServerConfiguration item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + "  —  " + item.baseUrl());
            }
        });
        serverBox.valueProperty().addListener((obs, old, current) -> updateSummary());

        force.setTooltip(new Tooltip("Sends the write without If-Match, so the server accepts"
                + " it even though somebody else has changed the resource since you loaded it."));
        unversioned.setTooltip(new Tooltip("Only for a server that reports no version. Your"
                + " changes could overwrite someone else's without any warning."));

        VBox content = new VBox(10, labelled("Server:", serverBox), new Separator(), summary,
                force, unversioned);
        content.setPadding(new Insets(14));
        getDialogPane().setContent(content);
        updateSummary();
    }

    /**
     * The chosen server and whether the user asked to bypass the version check.
     *
     * <p>Only non-null when a server is actually selected, so an empty list produces no
     * plan and the dialog simply closes.</p>
     */
    private Plan plan() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            return null;
        }
        return new Plan(server, force.isSelected() || unversioned.isSelected());
    }

    /**
     * Describes the write in the terms the user is about to commit to.
     *
     * <p>Three facts, because all three change what the decision means: the verb, the
     * address being written, and whether a conflicting change would even be noticed. The
     * third is the one most easily glossed over, so when the server reported no version
     * the box offering to send without checking is shown rather than the conflict
     * warning being quietly omitted.</p>
     */
    private void updateSummary() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            summary.setText("No server is configured. Add one under Tools > FHIR Servers.");
            force.setManaged(false);
            unversioned.setManaged(false);
            return;
        }
        boolean update = origin != null && origin.isSaved();
        String type = origin != null ? origin.resourceType() : typeName;
        String address = update ? origin.resourceType() + "/" + origin.resourceId() : "a new " + type;

        StringBuilder text = new StringBuilder(update ? "Overwrite " : "Create ")
                .append(address).append(" on ").append(server.name()).append('?')
                .append("\n\nThis changes data on ").append(server.baseUrl()).append('.');
        if (update && !origin.hasVersion()) {
            text.append("\n\nThe server reported no version when this resource was read, so a")
                    .append(" change made by somebody else cannot be detected. The write will")
                    .append(" overwrite their version without warning.");
            unversioned.setManaged(true);
            unversioned.setSelected(false);
        } else {
            unversioned.setManaged(false);
        }
        // Force is only meaningful once a version exists to conflict with.
        force.setManaged(update && origin.hasVersion());
        summary.setText(text.toString());
    }

    private static HBox labelled(String text, javafx.scene.Node control) {
        HBox row = new HBox(10, new Label(text), control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
