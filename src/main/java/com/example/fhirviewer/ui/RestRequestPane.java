package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.TransportSecurity;
import com.example.fhirviewer.server.rest.CurlCommand;
import com.example.fhirviewer.server.rest.RestHeaders;
import com.example.fhirviewer.server.rest.RestMethod;
import com.example.fhirviewer.server.rest.RestRequest;

/**
 * Everything a user fills in before pressing Send.
 *
 * <p>It holds no state of its own beyond the widgets: {@link #form()} reads the fields into
 * a {@link RestConsoleForm} and the caller works with that. That is the same split
 * {@link ServerOperationForm} makes, and it is why this class can be large without the
 * decisions inside it becoming untestable — what a request is, and whether it may be sent,
 * are both answered by code with no JavaFX in it.
 *
 * <p><b>The response pane is a sibling, not a child.</b> This class is what you fill in;
 * {@link RestResponsePane} is what came back. Neither knows how the other is laid out.
 */
public final class RestRequestPane extends VBox {

    /** Label for the free-URL option; it is not a server and has no configuration. */
    public static final String CUSTOM = "Custom URL…";

    /**
     * One entry in the server picker.
     *
     * <p>A record rather than the configuration itself, because the picker also has to
     * offer "Custom URL…" and that is not a server. Rendering is {@link #toString()},
     * which is what a {@code ComboBox} shows without needing a cell factory — the class it
     * would otherwise take, {@code ComboBoxCellConverter}, does not exist in JavaFX 25.
     */
    private record ServerChoice(FhirServerConfiguration server, String label) {

        static ServerChoice custom() {
            return new ServerChoice(null, CUSTOM);
        }

        static ServerChoice of(FhirServerConfiguration server) {
            return new ServerChoice(server, server.name() + " — " + server.baseUrl());
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ComboBox<ServerChoice> serverBox = new ComboBox<>();
    private final TextField baseUrlField = new TextField();
    private final ComboBox<RestMethod> methodBox = new ComboBox<>();
    private final TextField pathField = new TextField();
    private final Label urlPreview = new Label();
    private final ComboBox<ServerAuthKind> authBox = new ComboBox<>();
    private final TextField userNameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField tokenField = new TextField();
    private final Button fetchTokenButton = new Button("Fetch token…");
    private final Label transportWarning = new Label();
    private final TableView<RestParameterList.Parameter> parameterGrid = new TableView<>();
    private final TableView<RestParameterList.Parameter> headerGrid = new TableView<>();
    private final TextArea bodyArea = new TextArea();
    private final TextField contentTypeField = new TextField();
    private final Button pasteCurlButton = new Button("Paste cURL…");
    private final Button copyCurlButton = new Button("Copy as cURL");

    private final ObservableList<RestParameterList.Parameter> parameters =
            FXCollections.observableArrayList();
    private final ObservableList<RestParameterList.Parameter> headers =
            FXCollections.observableArrayList();

    private boolean usingCustomUrl;

    public RestRequestPane(List<FhirServerConfiguration> servers, FhirServerConfiguration preselect,
            Consumer<String> onFetchToken) {
        super(10);
        getStyleClass().add("rest-request-pane");
        setPadding(new Insets(12));

        configureControls();
        for (FhirServerConfiguration server : servers) {
            ServerChoice choice = ServerChoice.of(server);
            serverBox.getItems().add(choice);
            if (server.equals(preselect)) {
                serverBox.getSelectionModel().select(choice);
            }
        }
        if (preselect == null) {
            serverBox.getSelectionModel().select(ServerChoice.custom());
        }

        getChildren().addAll(addressSection(), authSection(),
                parameterSection(), headerSection(), bodySection());

        serverBox.setOnAction(event -> {
            usingCustomUrl = selectedServer() == null;
            if (!usingCustomUrl) {
                baseUrlField.clear();
            }
            refresh();
        });
        methodBox.setOnAction(event -> refresh());
        authBox.setOnAction(event -> refresh());
        baseUrlField.textProperty().addListener((o, was, now) -> {
            if (now != null && !now.isBlank()) {
                serverBox.getSelectionModel().clearSelection();
                usingCustomUrl = true;
            }
            refresh();
        });
        pathField.textProperty().addListener((o, was, now) -> refresh());
        fetchTokenButton.setOnAction(event -> {
            if (onFetchToken != null) {
                onFetchToken.accept(tokenField.getText());
            }
        });

        refresh();
    }

private void configureControls() {
        serverBox.setPrefWidth(280);
        serverBox.getItems().add(ServerChoice.custom());

        methodBox.setPrefWidth(110);
        methodBox.getItems().setAll(RestMethod.values());
        methodBox.getSelectionModel().select(RestMethod.GET);

        authBox.getItems().setAll(ServerAuthKind.values());
        authBox.getSelectionModel().select(ServerAuthKind.ANONYMOUS);

        pathField.setPromptText("Patient/123, or /Patient?name=Smith");
        urlPreview.getStyleClass().add("status-muted");
        urlPreview.setWrapText(true);

        userNameField.setPromptText("user name");
        passwordField.setPromptText("password");
        tokenField.setPromptText("access token");
        tokenField.setPrefColumnCount(40);

        contentTypeField.setText(RestRequest.DEFAULT_CONTENT_TYPE);
        contentTypeField.setPrefColumnCount(24);

        transportWarning.getStyleClass().add("status-error");
        transportWarning.setWrapText(true);
        transportWarning.setVisible(false);
    }

    /** The address: which server, which verb, and the two halves of the URL. */
    private VBox addressSection() {
        VBox box = new VBox(6);
        box.getChildren().addAll(sectionLabel("Request"),
                row(serverBox, methodBox),
                labelled("Base URL", baseUrlField),
                labelled("Path", pathField),
                urlPreview,
                row(pasteCurlButton, copyCurlButton));
        return box;
    }

    /** The credentials, with only the fields the chosen kind actually needs. */
    private VBox authSection() {
        return new VBox(6,
                sectionLabel("Authentication"),
                row(authBox, fetchTokenButton),
                labelled("User name", userNameField),
                labelled("Password", passwordField),
                labelled("Bearer token", tokenField),
                transportWarning);
    }

    private VBox parameterSection() {
        return new VBox(6,
                sectionLabel("Query parameters"),
                gridFor(parameterGrid, parameters, "Name", "Value",
                        () -> removeSelected(parameterGrid, parameters)),
                new Label("_include may be repeated; order is preserved."));
    }

    private VBox headerSection() {
        return new VBox(6,
                sectionLabel("Headers"),
                gridFor(headerGrid, headers, "Name", "Value",
                        () -> removeSelected(headerGrid, headers)));
    }

    private VBox bodySection() {
        return new VBox(6,
                sectionLabel("Request body"),
                bodyArea,
                labelled("Content type", contentTypeField));
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static javafx.scene.layout.Region labelled(String label, javafx.scene.Node field) {
        FlowPane box = new FlowPane(8, 8, new Label(label), field);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    private static javafx.scene.layout.Region row(javafx.scene.Node... nodes) {
        // A FlowPane for the same reason the response pane's buttons use one: an HBox cannot
        // wrap, so on a narrow pane the right-hand node is clipped rather than moved down.
        FlowPane row = new FlowPane(8, 8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

/**
     * An editable two-column grid over one of the value lists.
     *
     * <p>Rows are edited in place and written straight back into the backing list, so a row
     * that is half typed survives: swapping the whole list on every keystroke is what
     * loses work when the grid is mid-edit.
     */
    private VBox gridFor(TableView<RestParameterList.Parameter> grid,
            ObservableList<RestParameterList.Parameter> rows,
            String nameTitle, String valueTitle, Runnable onRemove) {
        grid.setEditable(true);
        grid.setPrefHeight(120);
        grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        grid.setItems(rows);

        TableColumn<RestParameterList.Parameter, String> name = new TableColumn<>(nameTitle);
        name.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        name.setCellFactory(column -> editingCell(nameTitle, rows,
                ParameterField.NAME));

        TableColumn<RestParameterList.Parameter, String> value = new TableColumn<>(valueTitle);
        value.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().value()));
        value.setCellFactory(column -> editingCell(valueTitle, rows, ParameterField.VALUE));

        grid.getColumns().setAll(name, value);

        Button add = new Button("Add");
        add.setOnAction(event -> rows.add(new RestParameterList.Parameter("", "")));
        Button remove = new Button("Remove selected");
        remove.setOnAction(event -> onRemove.run());
        return new VBox(4, grid, new HBox(8, add, remove));
    }

    /** Which half of a grid row a cell edits. */
    private enum ParameterField {
        NAME, VALUE
    }

    /**
     * A cell holding a text field, written back by index when focus leaves it.
     *
     * <p>Index rather than row identity: two rows of a parameter grid can legitimately be
     * identical — {@code _include=Patient:organization} twice is a legal repeat — and
     * finding "the row I meant" by value would edit the wrong one of them.
     */
    private TableCell<RestParameterList.Parameter, String> editingCell(String title,
            ObservableList<RestParameterList.Parameter> rows, ParameterField field) {
        return new TableCell<RestParameterList.Parameter, String>() {
            private final TextField editor = new TextField();

            {
                // Committing on focus loss and on Enter, so a row cannot be left holding a
                // value the grid does not know about. Both are set once, not per render.
                editor.focusedProperty().addListener((o, was, is) -> {
                    if (!is) {
                        commit();
                    }
                });
                editor.setOnAction(event -> commit());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                int index = getIndex();
                if (empty || index < 0 || index >= rows.size()) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                editor.setTooltip(new Tooltip(title));
                RestParameterList.Parameter row = rows.get(index);
                editor.setText(field == ParameterField.NAME ? row.name() : row.value());
                setText(null);
                setGraphic(editor);
            }

            private void commit() {
                int index = getIndex();
                if (index < 0 || index >= rows.size()) {
                    return;
                }
                RestParameterList.Parameter row = rows.get(index);
                rows.set(index, field == ParameterField.NAME
                        ? new RestParameterList.Parameter(editor.getText(), row.value())
                        : new RestParameterList.Parameter(row.name(), editor.getText()));
                getTableView().refresh();
            }
        };
    }

    private static void removeSelected(TableView<RestParameterList.Parameter> grid,
            ObservableList<RestParameterList.Parameter> rows) {
        int index = grid.getSelectionModel().getSelectedIndex();
        if (index >= 0 && index < rows.size()) {
            rows.remove(index);
        }
    }

/**
     * The current contents of every field, as a form the caller can validate and build.
     *
     * <p>Read fresh on each call rather than kept in sync, so there is exactly one place
     * where "what the user sees" and "what would be sent" are defined.
     */
    public RestConsoleForm form() {
        return new RestConsoleForm()
                .baseUrl(baseUrlOf())
                .method(methodBox.getValue())
                .path(pathField.getText())
                .parameters(parametersList())
                .headers(headerList())
                .body(bodyArea.getText())
                .contentType(contentTypeField.getText())
                .authentication(authBox.getValue(), userNameField.getText(),
                        passwordField.getText(), tokenField.getText());
    }

    /**
     * The grid rows as a parameter list.
     *
     * <p>Reused directly rather than round-tripped through a query string: the rows are
     * already an ordered, repeatable list, and re-encoding every value to decode it again
     * would be work for nothing.
     */
    private RestParameterList parametersList() {
        RestParameterList list = RestParameterList.empty();
        for (RestParameterList.Parameter parameter : parameters) {
            list = list.add(parameter.name(), parameter.value());
        }
        return list;
    }

    /**
     * The header rows as a header list.
     *
     * <p>Built through {@link RestHeaderList#add} so a row whose name is credential-bearing
     * is skipped rather than sent. One bad row must not stop the request going out with the
     * rest of what the user typed, and the console has already dealt with the
     * {@code Authorization} case — see {@link #applyCurl}.
     */
    private RestHeaderList headerList() {
        RestHeaderList list = RestHeaderList.empty();
        for (RestParameterList.Parameter header : headers) {
            if (header.name().isBlank()
                    || com.example.fhirviewer.server.rest.RestHeaders.isSecret(header.name())) {
                continue;
            }
            try {
                list = list.add(header.name(), header.value());
            } catch (IllegalArgumentException refused) {
                // Belt to braces: the check above already covers this name.
            }
        }
        return list;
    }

    /** What is still missing, for the dialog's Send button and status line. */
    public Optional<String> problem() {
        return form().problem();
    }

    private String baseUrlOf() {
        FhirServerConfiguration selected = selectedServer();
        return usingCustomUrl || selected == null ? baseUrlField.getText() : selected.baseUrl();
    }

    /** The configured server currently picked, or {@code null} for a custom URL. */
    private FhirServerConfiguration selectedServer() {
        ServerChoice choice = serverBox.getValue();
        return choice == null ? null : choice.server();
    }

/** Re-reads the fields and updates everything that depends on them. */
    private void refresh() {
        ServerAuthKind kind = authBox.getValue();
        boolean anonymous = kind == null || kind.isAnonymous();

        setShown(userNameField, kind != null && kind.needsUserName());
        setShown(tokenField, kind == ServerAuthKind.BEARER);
        setShown(fetchTokenButton, kind == ServerAuthKind.BEARER);

        boolean acceptsBody = methodBox.getValue() != null && methodBox.getValue().allowsRequestBody();
        bodyArea.setDisable(!acceptsBody);
        contentTypeField.setDisable(!acceptsBody);

        urlPreview.setText(form().resolvedUrl());

        String warning = anonymous ? null : TransportSecurity.warningFor(baseUrlOf());
        transportWarning.setText(warning == null ? "" : warning);
        transportWarning.setVisible(warning != null);
    }

    private static void setShown(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

/**
     * Fills the form in from a pasted cURL command.
     *
     * <p>An {@code Authorization} header in the command is <em>not</em> copied into the
     * header grid, which would refuse it. It is handed to {@code onAuthorization} instead so
     * the dialog can offer it to the auth selector: the value did come from a text box the
     * user pasted, and quietly adopting it would be a surprise, while silently dropping it
     * would produce a 401 with nothing to explain it.
 *
     * @throws IllegalArgumentException when the command asks for something this project will
     *         not do, such as {@code --insecure}
     */
    public boolean pasteCurl(String command, BiConsumer<String, String> onAuthorization) {
        if (!startsLikeCurl(command)) {
            // Refused rather than half-applied. CurlCommand.parse is deliberately lenient —
            // it will take any text and make its first word the URL — so without this a
            // paragraph of prose pastes in as a request with a nonsense address, and the form
            // is quietly wrong instead of the button simply saying no.
            return false;
        }
        java.util.Optional<CurlCommand.CurlRequest> parsed = CurlCommand.parse(command);
        parsed.ifPresent(found -> applyCurl(found, onAuthorization));
        // Whether anything was recognised. Returning nothing would force the caller to guess,
        // and "the clipboard was not a curl command" and "it was, the form is filled in" would
        // look exactly the same — which is how a button with no handler and a button that
        // quietly declined both read as "does nothing".
        return parsed.isPresent();
    }

    /**
     * True when the first word of the text is {@code curl}.
     *
     * <p>The check lives here rather than in {@link CurlCommand#parse}: parsing without the
     * program name is a thing a caller may legitimately want, so "is this a cURL command?" is
     * a question about the paste button rather than about the format.
     *
     * <p>A leading shell prompt is skipped, because a command copied straight out of a
     * terminal usually arrives with one.
     */
    private static boolean startsLikeCurl(String command) {
        if (command == null) {
            return false;
        }
        String text = command.stripLeading();
        while (text.startsWith("$ ")) {
            text = text.substring(2).stripLeading();
        }
        if (text.isEmpty()) {
            return false;
        }
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return "curl".equalsIgnoreCase(text.substring(0, end));
    }

    private void applyCurl(CurlCommand.CurlRequest parsed, BiConsumer<String, String> onAuthorization) {
        String url = parsed.url();
        int query = url.indexOf('?');
        baseUrlField.setText(query < 0 ? url : url.substring(0, query));
        pathField.setText("/");
        serverBox.getSelectionModel().clearSelection();
        usingCustomUrl = true;

        methodBox.getSelectionModel().select(parsed.method());
        bodyArea.setText(parsed.body() == null ? "" : parsed.body());

        parameters.clear();
        RestParameterList.parse(query < 0 ? "" : url.substring(query + 1))
                .entries().forEach(parameters::add);

        headers.clear();
        for (CurlCommand.CurlHeader header : parsed.headers()) {
            if (RestHeaders.isSecret(header.name())) {
                if (onAuthorization != null) {
                    onAuthorization.accept(header.value(), header.name());
                }
                continue;
            }
            headers.add(new RestParameterList.Parameter(header.name(), header.value()));
        }
        refresh();
    }

/** The current request as a cURL command, with credentials left out. */
    public String asCurl() {
        RestConsoleForm current = form();
        String url = current.resolvedUrl();
        if (url == null || url.isBlank()) {
            return "";
        }
        List<CurlCommand.CurlHeader> headerRows = new java.util.ArrayList<>();
        for (RestParameterList.Parameter header : headers) {
            if (!header.name().isBlank()) {
                headerRows.add(new CurlCommand.CurlHeader(header.name(), header.value()));
            }
        }
        String body = current.body();
        return CurlCommand.render(new CurlCommand.CurlRequest(current.method(), url, headerRows,
                body == null || body.isBlank() ? null : body));
    }

    /** Puts a fetched token into the field and selects bearer auth. */
    public void adoptToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokenField.setText(token);
        if (authBox.getValue() != ServerAuthKind.BEARER) {
            authBox.getSelectionModel().select(ServerAuthKind.BEARER);
        }
        refresh();
    }

    /**
     * Fills the Basic credentials and selects basic auth.
     *
     * <p>For a pasted cURL whose {@code Authorization} was {@code Basic}: that value is
     * base64 of {@code name:secret}, so it can be put back where the form expects it instead
     * of being dropped. Dropping it would send an unauthenticated request and produce a 401
     * with nothing to explain it.
     */
    public void adoptBasicCredentials(String userName, String password) {
        userNameField.setText(userName == null ? "" : userName);
        passwordField.setText(password == null ? "" : password);
        if (authBox.getValue() != ServerAuthKind.BASIC) {
            authBox.getSelectionModel().select(ServerAuthKind.BASIC);
        }
        refresh();
    }

    /**
     * Fills the token field without changing the auth selector.
     *
     * <p>For a pasted cURL whose {@code Authorization} value the user chose to keep: the
     * kind is left alone because the user may have already chosen the right one, and
     * silently switching to bearer behind their back would be a second surprise.
     */
    public void adoptTokenKeepingAuth(String token) {
        if (token != null && !token.isBlank()) {
            tokenField.setText(token);
            refresh();
        }
    }

    /** Wipes every credential field. Called when the console closes. */
    public void forgetCredentials() {
        passwordField.clear();
        tokenField.clear();
    }

    /** Sets the path, for the dialog to steer a pasted request at a Bundle entry. */
    public void setPath(String path) {
        pathField.setText(path);
        refresh();
    }

    /**
     * What the pane currently holds, as something storable.
     *
     * <p>The password and the token are not part of {@link RestConsoleState} and so cannot be
     * captured here even by accident — which is the point. A credential in this application
     * has exactly one route to disk, and this is not it.
     */
    public RestConsoleState captureState(double windowWidth, double windowHeight,
            double dividerPosition) {
        FhirServerConfiguration selected = selectedServer();
        return new RestConsoleState(
                selected == null ? "" : selected.baseUrl(),
                methodBox.getValue(),
                baseUrlField.getText(),
                pathField.getText(),
                List.copyOf(parameters),
                List.copyOf(headers),
                contentTypeField.getText(),
                authBox.getValue(),
                userNameField.getText(),
                windowWidth, windowHeight, dividerPosition);
    }

    /**
     * Fills the pane from a remembered state.
     *
     * <p>A server that is still configured is re-selected by name; anything else — a deleted
     * server, or a URL typed by hand — is restored as a custom base URL, so the console comes
     * back looking the way the user left it rather than silently switched to something else.
     */
    public void restoreState(RestConsoleState state) {
        if (state == null) {
            return;
        }
        ServerChoice match = null;
        for (ServerChoice choice : serverBox.getItems()) {
            if (choice.server() != null
                    && choice.server().baseUrl().equals(state.serverBaseUrl())) {
                match = choice;
                break;
            }
        }
        if (match != null) {
            serverBox.getSelectionModel().select(match);
            usingCustomUrl = false;
        } else {
            serverBox.getSelectionModel().select(ServerChoice.custom());
            usingCustomUrl = true;
            baseUrlField.setText(state.baseUrl());
        }
        if (state.method() != null) {
            methodBox.getSelectionModel().select(state.method());
        }
        pathField.setText(state.path() == null ? "" : state.path());
        if (state.contentType() != null && !state.contentType().isBlank()) {
            contentTypeField.setText(state.contentType());
        }
        if (state.authKind() != null) {
            authBox.getSelectionModel().select(state.authKind());
        }
        userNameField.setText(state.userName() == null ? "" : state.userName());

        // Cleared first, so a remembered list replaces rather than adds to whatever is there.
        parameters.clear();
        state.parameters().forEach(parameters::add);
        headers.clear();
        state.headers().forEach(headers::add);

        // The body is deliberately not restored: see RestConsoleState.
        bodyArea.clear();
        refresh();
    }

/** The cURL buttons, so the dialog can place them with its own controls. */
    public Button pasteCurlButton() {
        return pasteCurlButton;
    }

    public Button copyCurlButton() {
        return copyCurlButton;
    }

    /**
     * Wires the copy button, which needs a clipboard the pane does not own.
     *
     * <p>Reading the clipboard also needs care: a headless or remote session can have no
     * clipboard at all, and {@code getSystemClipboard()} throws rather than returning null.
     * Both actions are therefore supplied by the dialog, which is also where the "paste
     * something that is not a curl" message belongs.
     */
    public void setCurlActions(Runnable onPaste, Runnable onCopy) {
        pasteCurlButton.setOnAction(event -> onPaste.run());
        copyCurlButton.setOnAction(event -> onCopy.run());
    }

    /**
     * Puts the current request on the system clipboard as a cURL command.
     *
     * @return true only when something really was written, so the caller can say so rather
     *         than report a copy that never happened. Two separate ways to fail: there is no
     *         URL to render yet, and there is no clipboard to write to — a headless or remote
     *         session has none, and {@code getSystemClipboard()} throws rather than returning
     *         null.
     */
    public boolean copyToClipboard() {
        String command = asCurl();
        if (command.isBlank()) {
            return false;
        }
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(command);
            Clipboard.getSystemClipboard().setContent(content);
            return true;
        } catch (IllegalStateException noClipboard) {
            return false;
        }
    }

    /** The clipboard's text, or the empty string when there is no clipboard. */
    public String clipboardText() {
        try {
            String text = Clipboard.getSystemClipboard().getString();
            return text == null ? "" : text;
        } catch (IllegalStateException | IllegalArgumentException noClipboard) {
            return "";
        }
    }
}