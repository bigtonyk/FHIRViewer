package com.example.fhirviewer.ui;

import java.util.Arrays;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import com.example.fhirviewer.server.TokenEndpointClient;
import com.example.fhirviewer.server.TokenFetchResult;
import com.example.fhirviewer.server.TransportSecurity;

/**
 * Obtains a bearer token with an OAuth 2.0 client-credentials grant.
 *
 * <p>Separate from {@link RestRequestPane} because it is a different decision: choosing how
 * a request authenticates is the console's business, and going away to an identity provider
 * to obtain a token is a separate act with its own credentials. Keeping them apart is also
 * what lets a pasted token and a fetched token end up in exactly the same place.
 *
 * <p><b>Nothing here is stored.</b> The client secret is held as a {@code char[]} for the
 * lifetime of this dialog and wiped in {@link #wipeSecret()} whether the grant succeeded,
 * failed or was never attempted. The token goes back to the console and is not persisted
 * there either — see {@code docs/plans/11_REST_CONSOLE_UI.md}, decision DD3.
 *
 * <p><b>The plain-HTTP warning is the project's existing one.</b> A client secret crossing
 * a network unencrypted is the same risk as a password crossing one unencrypted, and
 * {@link TransportSecurity} already words that message. Reusing it keeps the console from
 * being the one screen that warns about this differently from every other.
 */
public class TokenFetchDialog extends Dialog<Boolean> {

    private final TextField endpointField = new TextField();
    private final TextField clientIdField = new TextField();
    private final PasswordField clientSecretField = new PasswordField();
    private final TextField scopeField = new TextField();
    private final Label warningLabel = new Label();
    private final Label statusLabel = new Label(" ");
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Button fetchButton = new Button("Fetch token");

    private final RestConsoleDialog console;
    private BackgroundTasks.Running running;

    public TokenFetchDialog(Window owner, RestConsoleDialog console) {
        this.console = console;
        setTitle("Fetch bearer token");
        setResizable(false);
        if (owner != null) {
            initOwner(owner);
        }

        endpointField.setPromptText("https://auth.example.com/oauth/token");
        clientIdField.setPromptText("client id");
        clientSecretField.setPromptText("client secret");
        scopeField.setPromptText("optional, e.g. system/Patient.read");
        endpointField.setPrefColumnCount(44);
        warningLabel.getStyleClass().add("status-error");
        warningLabel.setWrapText(true);
        warningLabel.setVisible(false);

        fetchButton.setDefaultButton(true);
        fetchButton.setOnAction(event -> fetch());
        busy.setVisible(false);
        busy.setMaxSize(16, 16);
        statusLabel.getStyleClass().add("status-muted");
        statusLabel.setWrapText(true);

        getDialogPane().setContent(layout());
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        endpointField.textProperty().addListener((o, was, now) -> refreshWarning());
        setOnCloseRequest(event -> wipeSecret());
    }

private GridPane layout() {
        GridPane grid = new GridPane(8, 6);
        grid.setPadding(new Insets(14));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        grid.add(new Label("Token endpoint"), 0, row);
        grid.add(endpointField, 1, row++);
        grid.add(new Label("Client id"), 0, row);
        grid.add(clientIdField, 1, row++);
        grid.add(new Label("Client secret"), 0, row);
        grid.add(clientSecretField, 1, row++);
        grid.add(new Label("Scope"), 0, row);
        grid.add(scopeField, 1, row);
        grid.add(warningLabel, 0, row++, 2, 1);
        grid.add(new HBox(8, fetchButton, busy), 0, row++, 2, 1);
        grid.add(statusLabel, 0, row, 2, 1);
        return grid;
    }

    private void refreshWarning() {
        String warning = TransportSecurity.warningFor(endpointField.getText());
        warningLabel.setText(warning == null ? "" : warning);
        warningLabel.setVisible(warning != null);
    }

    /**
     * Runs the grant on a background thread and hands the token back to the console.
     *
     * <p>The secret is copied once — a {@code PasswordField} holds a {@code String} and
     * cannot hand out a {@code char[]} — and the copy is wiped immediately after the
     * request, whether it succeeded or not.
     */
    private void fetch() {
        String endpoint = endpointField.getText();
        String clientId = clientIdField.getText();
        String scope = scopeField.getText();
        char[] secret = clientSecretField.getText().toCharArray();

        if (endpoint.isBlank() || clientId.isBlank() || secret.length == 0) {
            Arrays.fill(secret, '\0');
            setStatus("Enter the token endpoint, client id and client secret.", true);
            return;
        }

        fetchButton.setDisable(true);
        busy.setVisible(true);
        setStatus("Requesting a token ...", false);

        running = BackgroundTasks.runAttempt("rest-console-token",
                () -> BackgroundTasks.attempt(() -> {
                    try {
                        return new TokenEndpointClient().fetch(endpoint, clientId, secret, scope);
                    } finally {
                        // Wiped whether the grant succeeded, failed or threw, so a live
                        // secret can never be left lying in an array.
                        Arrays.fill(secret, '\0');
                    }
                }),
                attempt -> {
                    busy.setVisible(false);
                    fetchButton.setDisable(false);
                    if (attempt.cancelled()) {
                        setStatus("The token request was cancelled.", false);
                    } else if (!attempt.succeeded()) {
                        setStatus(ServerErrors.redact(attempt.failure()), true);
                    } else {
                        TokenFetchResult token = attempt.value();
                        console.adoptFetchedToken(token.accessToken(), token.describe());
                        setStatus(token.describe(), false);
                        close();
                    }
                });
    }

    private void setStatus(String message, boolean error) {
        statusLabel.getStyleClass().remove("status-error");
        if (error) {
            statusLabel.getStyleClass().add("status-error");
        }
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
    }

    /** Wipes the secret on every way out of the dialog. */
    private void wipeSecret() {
        clientSecretField.clear();
        if (running != null) {
            running.cancel();
            running = null;
        }
    }
}