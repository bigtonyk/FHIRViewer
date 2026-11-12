package com.spiraleyes.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.spiraleyes.fhirviewer.fhir.ElementProperty;
import com.spiraleyes.fhirviewer.model.BundleEntryInfo;
import com.spiraleyes.fhirviewer.model.ElementInfo;
import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ResourceFormat;
import com.spiraleyes.fhirviewer.model.ResourceNode;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.ResourceEditorService;
import com.spiraleyes.fhirviewer.service.ResourceLoadException;
import com.spiraleyes.fhirviewer.service.SourceEditorService;
import com.spiraleyes.fhirviewer.util.FileSupport;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * The document: which resource is loaded and displayed, the file operations on it, the views it
 * is rendered into, the edit history, and the element edits themselves.
 *
 * <p>This is M1's fifth step (R4 §7): the state and the editor core that used to sit in
 * {@code MainWindow} beside the layout, the menus and the server wiring. The window keeps the
 * views, because {@code buildLayout} builds them; the session is handed them through
 * {@link DocumentViews} and owns everything else about the document — loaded resource, origin,
 * displayed entry, label, selection and the undo stack. It also implements {@link DocumentPort},
 * so the server actions and server screens talk to the same object rather than to the window.</p>
 *
 * <p>Behaviour is unchanged: the display order (tree, then documents, then Bundle, then
 * selection), the single history that {@code display} clears and {@link #applySourceText}
 * restores, the {@code markDirty} on every edit, the title asterisk and the undo cap are the
 * code that was there before, moved.</p>
 */
final class DocumentSession implements DocumentPort {

    private static final String READY_STATUS =
            "Ready. Use File > Open to load a FHIR JSON or XML resource.";
    /** How many edits can be undone; older snapshots are dropped. */
    private static final int MAX_UNDO_DEPTH = 20;

    private final Stage owner;
    private final FhirService fhirService;
    private final ResourceEditorService editorService;
    private final SourceEditorService sourceEditorService;
    private final ThemeManager themeManager;
    private final DocumentViews views;
    private final Consumer<String> status;
    private final BiConsumer<String, Throwable> failure;
    private final Runnable refreshTitle;
    private final Runnable editActions;
    private final Runnable profileChoices;
    private final Consumer<Boolean> busy;

    /** The resource loaded from a file or sample. */
    private LoadedResource loaded;
    /** The resource currently shown: the loaded resource or one of its Bundle entries. */
    private IBaseResource displayed;
    private String label = "";
    /** When a Bundle entry is being displayed, the entry it came from. */
    private BundleEntryInfo entry;
    /** Where the displayed resource came from on a FHIR server, or {@code null}. */
    private ServerOrigin origin;
    /** The element selected in the tree, or {@code null}. */
    private ResourceNode selected;
    /**
     * Snapshots of the loaded resource taken before every edit, newest first. Undo shows
     * a snapshot again as the loaded resource, so the resource object is replaced rather
     * than mutated back; the depth is capped so a long editing session cannot grow
     * without bound.
     */
    private final Deque<IBaseResource> undo = new ArrayDeque<>();

    private File lastDirectory;

    DocumentSession(Stage owner, FhirService fhirService, ResourceEditorService editorService,
            SourceEditorService sourceEditorService, ThemeManager themeManager, DocumentViews views,
            Consumer<String> status, BiConsumer<String, Throwable> failure, Runnable refreshTitle,
            Runnable editActions, Runnable profileChoices, Consumer<Boolean> busy) {
        this.owner = owner;
        this.fhirService = fhirService;
        this.editorService = editorService;
        this.sourceEditorService = sourceEditorService;
        this.themeManager = themeManager;
        this.views = views;
        this.status = status;
        this.failure = failure;
        this.refreshTitle = refreshTitle;
        this.editActions = editActions;
        this.profileChoices = profileChoices;
        this.busy = busy;
    }

    // ------------------------------------------------------------------
    // DocumentPort
    // ------------------------------------------------------------------

    @Override
    public IBaseResource resource() {
        return displayed;
    }

    @Override
    public LoadedResource loaded() {
        return loaded;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public ServerOrigin origin() {
        return origin;
    }

    @Override
    public void show(LoadedResource resource, ServerOrigin newOrigin) {
        display(resource, newOrigin);
    }

    @Override
    public boolean confirmDiscard(String action) {
        return confirmUnsavedChanges(action);
    }

    @Override
    public String currentJson() {
        IBaseResource resource = currentTarget();
        return resource == null ? null : fhirService.toJson(resource);
    }

    @Override
    public void close() {
        closeResource();
    }

    @Override
    public void showValidation(com.spiraleyes.fhirviewer.model.ValidationReport report) {
        views.status().showValidation(report);
    }

    @Override
    public void busy(boolean isBusy) {
        busy.accept(isBusy);
    }

    // ------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------

    /**
     * Displays a resource and records where it came from.
     *
     * <p>A {@code null} origin means "not from a server", which is the case for every
     * file, sample and clipboard load. Clearing it on those paths is what stops a
     * resource read from a server from being written back to that server after the user
     * opens an unrelated file.
     */
    void display(LoadedResource resource, ServerOrigin newOrigin) {
        loaded = resource;
        displayed = resource.getResource();
        label = resource.getDisplayName();
        origin = newOrigin;
        selected = null;
        // A different resource starts a new editing history.
        undo.clear();

        entry = null;
        ResourceNode tree = fhirService.buildTree(resource, views.showUnpopulated());
        views.tree().show(tree);
        views.details().showNothingSelected();
        updateDocumentViews();
        views.status().clearValidation();
        updateBundleView();

        views.documentTabs().getSelectionModel().select(views.prettyTab());
        views.structureTabs().getSelectionModel().select(views.treeTab());

        status.accept("Loaded " + resource.getSourceName()
                + " - " + resource.getDisplayName()
                + " (" + resource.getFormat().getDisplayName()
                + ", " + countNodes(tree) + " elements shown)");
        profileChoices.run();
        refreshTitle.run();
    }

    /** Rebuilds the tree for the currently displayed resource (the View menu toggle). */
    void refreshTree() {
        if (loaded == null) {
            return;
        }
        String selectedPath = selected == null ? null : selected.getPath();
        if (entry == null) {
            views.tree().show(fhirService.buildTree(loaded, views.showUnpopulated()));
        } else {
            views.tree().show(fhirService.buildTree(entry, views.showUnpopulated()));
        }
        // Keep the element the user was looking at selected.
        views.tree().selectPath(selectedPath);
    }

    /** Shows the whole Bundle, or one of its entries, in every view. */
    void displayBundleEntry(BundleEntryInfo newEntry) {
        if (loaded == null) {
            return;
        }
        selected = null;
        if (newEntry == null) {
            entry = null;
            displayed = loaded.getResource();
            label = loaded.getDisplayName();
            views.tree().show(fhirService.buildTree(loaded, views.showUnpopulated()));
            updateDocumentViews();
            views.details().showNothingSelected();
            status.accept("Showing the Bundle resource itself: " + label);
            profileChoices.run();
            refreshTitle.run();
            return;
        }
        if (!newEntry.hasResource()) {
            status.accept("Bundle entry [" + newEntry.index() + "] contains no resource to display.");
            return;
        }

        entry = newEntry;
        displayed = newEntry.resource();
        label = newEntry.displayName();
        views.tree().show(fhirService.buildTree(newEntry, views.showUnpopulated()));
        updateDocumentViews();
        views.details().showNothingSelected();
        views.documentTabs().getSelectionModel().select(views.prettyTab());
        status.accept("Showing Bundle entry [" + newEntry.index() + "] " + newEntry.displayName());
        profileChoices.run();
        refreshTitle.run();
    }

    private void updateBundleView() {
        if (loaded == null || !loaded.isBundle()) {
            views.bundle().clear();
            return;
        }
        List<BundleEntryInfo> entries = fhirService.bundleEntries(loaded.getResource());
        views.bundle().showBundle(entries, loaded.getDisplayName());
    }

    private void updateDocumentViews() {
        if (displayed == null) {
            views.pretty().showNothing();
            views.json().showNothing();
            views.xml().showNothing();
            views.fhirPath().recalculate();
            return;
        }
        views.pretty().show(entry == null
                ? fhirService.buildPrettyView(loaded)
                : fhirService.buildPrettyView(entry));
        views.json().show(fhirService.toJson(displayed));
        views.xml().show(fhirService.toXml(displayed));
        // The FHIRPath entries are evaluated against the resource that just changed,
        // so their results stay in step with the tree, the Pretty View and JSON/XML.
        views.fhirPath().recalculate();
    }

    private int countNodes(ResourceNode node) {
        int count = 1;
        for (ResourceNode child : node.getChildren()) {
            count += countNodes(child);
        }
        return count;
    }

    /**
     * Navigates a double clicked tree element when it is a FHIR Reference whose
     * target exists in the loaded data set: the matching Bundle entry is shown.
     */
    void navigateToReference(ResourceNode node) {
        if (node == null
                || node.getElementInfo().getKind() != ElementInfo.Kind.REFERENCE
                || loaded == null || !loaded.isBundle()) {
            return;
        }
        String target = referenceTarget(node.getValueText());
        if (target == null) {
            return;
        }
        for (BundleEntryInfo candidate
                : flattenEntries(fhirService.bundleEntries(loaded.getResource()))) {
            if (target.equalsIgnoreCase(candidate.displayName())) {
                displayBundleEntry(candidate);
                return;
            }
        }
        status.accept("The referenced resource " + target + " is not part of the loaded Bundle.");
    }

    /** Extracts <code>Type/id</code> from a rendered reference value. */
    private static String referenceTarget(String valueText) {
        if (valueText == null || valueText.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([A-Za-z]+/[A-Za-z0-9\\-.]{1,64})").matcher(valueText);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** All entries of the Bundle, including nested Bundle-in-Bundle entries. */
    private static List<BundleEntryInfo> flattenEntries(List<BundleEntryInfo> entries) {
        List<BundleEntryInfo> all = new java.util.ArrayList<>();
        for (BundleEntryInfo candidate : entries) {
            all.add(candidate);
            all.addAll(flattenEntries(candidate.childEntries()));
        }
        return all;
    }

    /** Records the element selected in the tree and renders its detail. */
    void select(ResourceNode node) {
        selected = node;
        views.details().show(node);
        editActions.run();
        if (node != null && displayed != null) {
            views.pretty().show(fhirService.buildPrettyView(displayed, node));
            views.pretty().scrollToElement(node);
        }
        views.json().scrollToElement(node);
        views.xml().scrollToElement(node);
    }

    // ------------------------------------------------------------------
    // File operations
    // ------------------------------------------------------------------

    void openFile() {
        if (!confirmUnsavedChanges("opening another resource")) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open FHIR resource");
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("FHIR resources", "*.json", "*.xml"),
                new FileChooser.ExtensionFilter("FHIR JSON", "*.json"),
                new FileChooser.ExtensionFilter("FHIR XML", "*.xml"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        openPath(file.toPath());
    }

    void openPath(Path path) {
        runWithWaitCursor("Loading " + FileSupport.fileName(path) + " ...",
                () -> display(fhirService.openFile(path), null));
    }

    void openSample(String classpathResource) {
        if (!confirmUnsavedChanges("opening a sample")) {
            return;
        }
        runWithWaitCursor("Loading sample " + classpathResource + " ...",
                () -> display(fhirService.openSample(classpathResource), null));
    }

    /** Saves the loaded resource, asking for a file when it does not have one yet. */
    boolean save() {
        if (loaded == null) {
            status.accept("Nothing to save. Open or create a FHIR resource first.");
            return false;
        }
        Path target = loaded.getSourcePath();
        if (target == null) {
            return saveAs();
        }
        return write(target, loaded.getFormat());
    }

    /** Saves the loaded resource to a file chosen by the user. */
    boolean saveAs() {
        if (loaded == null) {
            status.accept("Nothing to save. Open or create a FHIR resource first.");
            return false;
        }
        ResourceFormat format = loaded.getFormat();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save resource as");
        chooser.setInitialFileName(suggestedFileName(loaded.getResource(), format));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("FHIR JSON", "*.json"),
                new FileChooser.ExtensionFilter("FHIR XML", "*.xml"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        chooser.setSelectedExtensionFilter(
                chooser.getExtensionFilters().get(format == ResourceFormat.XML ? 1 : 0));

        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return false;
        }
        lastDirectory = file.getParentFile();
        Path path = file.toPath();
        // The chosen file name decides the format, so "Save As patient.xml" writes XML.
        ResourceFormat written = ResourceFormat.fromFileName(file.getName()).orElse(format);
        if (!write(path, written)) {
            return false;
        }
        loaded.setSourcePath(path);
        loaded.setSourceName(FileSupport.fileName(path));
        loaded.setFormat(written);
        refreshTitle.run();
        return true;
    }

    private boolean write(Path path, ResourceFormat format) {
        try {
            FileSupport.writeText(path, fhirService.serialize(loaded.getResource(), format));
            loaded.markClean();
            refreshTitle.run();
            status.accept("Saved " + loaded.getDisplayName() + " to " + path);
            return true;
        } catch (IOException | RuntimeException e) {
            failure.accept("Could not save " + path, e);
            return false;
        }
    }

    /** Writes the displayed resource to a chosen file, leaving the document untouched. */
    void export(ResourceFormat format) {
        if (displayed == null) {
            status.accept("Nothing to export. Open a FHIR resource first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + format.getDisplayName());
        chooser.setInitialFileName(suggestedFileName(displayed, format));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format.getDisplayName(),
                        "*." + format.getExtension()));

        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        try {
            FileSupport.writeText(file.toPath(), fhirService.serialize(displayed, format));
            status.accept("Exported " + label + " to " + file.getAbsolutePath());
        } catch (IOException e) {
            failure.accept("Could not write " + file, e);
        }
    }

    private String suggestedFileName(IBaseResource resource, ResourceFormat format) {
        String type = resource.fhirType();
        var idElement = resource.getIdElement();
        String id = idElement != null && idElement.hasIdPart() ? idElement.getIdPart() : null;
        return (id == null ? type : type + "-" + id) + "." + format.getExtension();
    }

    private void runWithWaitCursor(String statusMessage, Runnable action) {
        status.accept(statusMessage);
        busy.accept(true);
        try {
            action.run();
        } catch (ResourceLoadException e) {
            failure.accept("Could not load the resource", e);
        } finally {
            busy.accept(false);
        }
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** Applies an edited value to the element selected in the tree. */
    void applyValueEdit(ResourceNode node, String text) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            status.accept("Select an element in the resource tree first.");
            return;
        }
        String path = node.getElementInfo().getPath();
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            if (node.getElementInfo().getKind() == ElementInfo.Kind.REFERENCE) {
                editorService.setReference(target, path, referenceValue(text));
            } else {
                editorService.setPrimitive(target, path, text);
            }
            refreshAfterEdit(path);
            status.accept("Updated " + path + " of " + label + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            failure.accept("Could not update " + path, e);
        }
    }

    /** Adds a new entry to the repeating element selected in the tree. */
    void addElementToSelection(ResourceNode node) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            status.accept("Select a repeating element in the resource tree first.");
            return;
        }
        String path = node.getElementInfo().getPath();
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            int existing = editorService.valueCount(target, path);
            editorService.addElement(target, path);
            // The new entry is the last one of the repeating element, so select it: its
            // value can then be filled in straight away.
            refreshAfterEdit(path + "[" + existing + "]", path);
            status.accept("Added a new " + node.getElementInfo().getName() + " entry to " + label + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            failure.accept("Could not add a " + node.getElementInfo().getName() + " entry", e);
        }
    }

    /**
     * Opens the dialog that lists the child elements the FHIR resource definition
     * allows under the element selected in the tree, then adds the picked element.
     * A primitive element is asked for an initial value right after it is added.
     */
    void addChildToSelection(ResourceNode node) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            status.accept("Select an element in the resource tree first.");
            return;
        }
        String parentPath = addableParentPath(node);
        List<ElementProperty> children;
        try {
            children = editorService.childElements(target, parentPath);
        } catch (RuntimeException e) {
            failure.accept("Could not list the child elements of " + parentPath, e);
            return;
        }
        if (children.isEmpty()) {
            status.accept("The FHIR definition has no child element that can be added to "
                    + parentPath + ".");
            return;
        }
        AddChildDialog dialog = new AddChildDialog(parentPath, children);
        dialog.initOwner(owner);
        themeDialog(dialog.getDialogPane());
        Optional<ElementProperty> picked = dialog.showAndWait();
        if (picked.isEmpty()) {
            return;
        }
        addChildElement(target, parentPath, picked.get());
    }

    /**
     * The path the Add child dialog works on. A repeating element without an entry
     * yet, such as {@code Patient.telecom} with no values, receives its first entry
     * first, so the dialog lists the children the new entry can take. A repeating
     * element with entries works on the last entry, matching the way the tree
     * groups the entries of a repeating element.
     */
    private String addableParentPath(ResourceNode node) {
        String path = node.getElementInfo().getPath();
        IBaseResource target = currentTarget();
        if (target == null) {
            return path;
        }
        boolean repeatingGroup = node.getElementInfo().getKind() == ElementInfo.Kind.COMPLEX
                && node.getElementInfo().isRepeating()
                && !path.endsWith("]");
        if (!repeatingGroup) {
            return path;
        }
        int entries = editorService.valueCount(target, path);
        if (entries > 0) {
            return path + "[" + (entries - 1) + "]";
        }
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            editorService.addElement(target, path);
            refreshAfterEdit(path + "[0]", path);
            return path + "[0]";
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            return path;
        }
    }

    /** Adds the child element the user picked in the Add child dialog. */
    private void addChildElement(IBaseResource target, String parentPath, ElementProperty child) {
        String childPath = parentPath + "." + child.name();
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            editorService.addNode(target, parentPath, child.name());
            // A new entry of a repeating element is addressed with its index, exactly
            // the way the tree names it, so the value can be filled in straight away.
            String newPath = child.isRepeating()
                    ? childPath + "[" + (editorService.valueCount(target, childPath) - 1) + "]"
                    : childPath;
            promptForInitialValue(target, newPath, child);
            refreshAfterEdit(newPath, childPath, parentPath);
            status.accept("Added " + newPath + " to " + label + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            failure.accept("Could not add " + childPath, e);
        }
    }

    /**
     * Asks for an initial value right after a primitive or reference element was
     * added, so the new element is not left empty. An empty answer keeps the
     * element as it is; complex elements continue in the tree instead.
     */
    private void promptForInitialValue(IBaseResource target, String newPath, ElementProperty child) {
        var added = editorService.resolveElement(target, newPath);
        boolean reference = added instanceof IBaseReference;
        if (!reference && !(added instanceof IPrimitiveType<?>)) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        dialog.setTitle("FHIR Resource Viewer");
        dialog.setHeaderText((reference ? "Reference target for " : "Initial value for ") + newPath);
        dialog.setContentText(reference
                ? "Target, for example Practitioner/123 (empty keeps the element without a target):"
                : "Value (empty keeps the element without a value):");
        themeDialog(dialog.getDialogPane());
        Optional<String> value = dialog.showAndWait();
        if (value.isEmpty() || value.get().isBlank()) {
            return;
        }
        try {
            if (reference) {
                editorService.setReference(target, newPath, referenceValue(value.get()));
            } else {
                editorService.setPrimitive(target, newPath, value.get());
            }
        } catch (RuntimeException e) {
            failure.accept("Could not set the value of " + newPath, e);
        }
    }

    /** Deletes the element selected in the tree, asking for confirmation first. */
    void deleteSelectedNode() {
        deleteNode(selected, true);
    }

    /**
     * Deletes the given element from the displayed resource.
     *
     * @param confirm when {@code true} a confirmation is shown for elements that
     *                remove more than a single value
     */
    void deleteNode(ResourceNode node, boolean confirm) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            status.accept("Select an element in the resource tree first.");
            return;
        }
        if (node.getParent() == null) {
            status.accept("The resource itself cannot be deleted; use File > Close instead.");
            return;
        }
        String path = node.getElementInfo().getPath();
        if (confirm && !confirmRemoval(node, path)) {
            return;
        }
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            editorService.deleteNode(target, path);
            String parentPath = node.getParent().getElementInfo().getPath();
            refreshAfterEdit(parentPath, path);
            status.accept("Deleted " + path + " from " + label + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            failure.accept("Could not delete " + path, e);
        }
    }

    /** Asks before deleting an element that removes a whole subtree of values. */
    private boolean confirmRemoval(ResourceNode node, String path) {
        if (node.getChildren().isEmpty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle("FHIR Resource Viewer");
        alert.setHeaderText("Delete " + path + "?");
        alert.setContentText("This removes the element with its "
                + (countNodes(node) - 1) + " child elements from " + label
                + ". The change can be undone.");
        themeDialog(alert.getDialogPane());
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }

    /** Applies the text of the JSON editor to the current resource. */
    void applyJsonSource(String text) {
        applySourceText(text, ResourceFormat.JSON);
    }

    /** Applies the text of the XML editor to the current resource. */
    void applyXmlSource(String text) {
        applySourceText(text, ResourceFormat.XML);
    }

    /**
     * Parses and validates edited source text, then replaces the current resource.
     *
     * <p>On failure nothing is replaced: the error is shown, the edited text is left
     * intact for correction, and the tree, Pretty View, FHIRPath results and
     * serialized views keep showing the current resource.</p>
     */
    private void applySourceText(String text, ResourceFormat format) {
        if (loaded == null || currentTarget() == null) {
            status.accept("Nothing to apply. Open a FHIR resource first.");
            return;
        }
        // Recorded before the parse so a successful replacement can be undone.
        IBaseResource snapshot = pushUndoSnapshot();
        Deque<IBaseResource> history = new ArrayDeque<>(undo);
        try {
            String sourceName = loaded.getSourceName() == null
                    ? label + "." + format.getExtension()
                    : loaded.getSourceName();
            SourceEditorService.AppliedSource applied =
                    sourceEditorService.apply(text, format, sourceName);
            replaceCurrentResource(applied, format);
            restoreUndoHistory(history);
        } catch (RuntimeException e) {
            restoreUndoHistory(history);
            discardUndoSnapshot(snapshot);
            failure.accept("Could not apply the edited " + format.getDisplayName(), e);
        }
    }

    /**
     * Swaps the loaded resource for a parsed replacement and refreshes every view
     * from it. The caller keeps the editing history, so applying source text can be
     * undone like any other edit.
     */
    private void replaceCurrentResource(SourceEditorService.AppliedSource applied,
            ResourceFormat editedFormat) {
        IBaseResource replacement = applied.resource();
        LoadedResource next = new LoadedResource(
                replacement,
                applied.format(),
                loaded.getSourceName(),
                sourceEditorService.serialize(replacement, applied.format()),
                loaded.getSourcePath());
        next.markDirty();
        // display() rebuilds the tree, the Pretty View, the serialized views, the
        // Bundle navigator and the title from the replacement resource.
        display(next, origin);
        status.accept("Applied the edited " + editedFormat.getDisplayName() + " to "
                + next.getDisplayName() + " (" + applied.report().getSummary() + ").");
    }

    /** Puts back the editing history that {@link #display} resets when it reloads a view. */
    private void restoreUndoHistory(Deque<IBaseResource> history) {
        undo.clear();
        undo.addAll(history);
        editActions.run();
    }

    /**
     * Marks the resource as changed and rebuilds every view for it. The first path that
     * still exists in the rebuilt tree is selected again, so an edit does not lose the
     * user's place.
     */
    private void refreshAfterEdit(String... pathsToSelect) {
        if (loaded == null) {
            return;
        }
        loaded.markDirty();
        views.tree().show(entry == null
                ? fhirService.buildTree(loaded, views.showUnpopulated())
                : fhirService.buildTree(entry, views.showUnpopulated()));
        // The documents are rebuilt before the selection is restored, because selecting
        // a node renders the element view of that node.
        updateDocumentViews();
        if (entry == null) {
            // While an entry is displayed the Bundle navigator must keep naming that entry,
            // so it is only rebuilt when the whole Bundle is shown.
            updateBundleView();
        }
        views.status().clearValidation();
        for (String path : pathsToSelect) {
            if (views.tree().selectPath(path)) {
                break;
            }
        }
        refreshTitle.run();
    }

    /** Records the state of the resource before an edit, so the edit can be undone. */
    private IBaseResource pushUndoSnapshot() {
        if (loaded == null) {
            return null;
        }
        IBaseResource snapshot = editorService.cloneResource(loaded.getResource());
        undo.push(snapshot);
        while (undo.size() > MAX_UNDO_DEPTH) {
            undo.removeLast();
        }
        editActions.run();
        return snapshot;
    }

    /** Forgets a snapshot when the edit it was taken for failed. */
    private void discardUndoSnapshot(IBaseResource snapshot) {
        if (snapshot != null) {
            undo.remove(snapshot);
            editActions.run();
        }
    }

    /** Restores the resource as it was before the last edit. */
    void undoEdit() {
        if (undo.isEmpty() || loaded == null) {
            status.accept("There is nothing to undo.");
            return;
        }
        Deque<IBaseResource> remaining = new ArrayDeque<>(undo);
        IBaseResource snapshot = remaining.pop();
        LoadedResource restored = new LoadedResource(
                snapshot,
                loaded.getFormat(),
                loaded.getSourceName(),
                null,
                loaded.getSourcePath());
        // Every snapshot is the state before one edit, so popping the last one restores
        // exactly what was loaded from disk.
        if (remaining.isEmpty()) {
            restored.markClean();
        } else {
            restored.markDirty();
        }
        // An undo is not a trip to another resource: the server origin and the Bundle entry
        // the user is looking at both survive it. The snapshot is a clone of the whole
        // Bundle, so the entry belongs to the resource being replaced and is found again by
        // position and label.
        int entryIndex = entry == null ? -1 : entry.index();
        String entryName = entry == null ? null : entry.displayName();
        display(restored, origin);
        if (entryIndex >= 0) {
            restoreDisplayedEntry(restored, entryIndex, entryName);
        }
        undo.clear();
        undo.addAll(remaining);
        editActions.run();
        status.accept(remaining.isEmpty()
                ? "Undid every change; " + restored.getDisplayName()
                        + " matches the saved resource again."
                : "Undid the last change to " + restored.getDisplayName() + ".");
    }

    /**
     * Returns to the Bundle entry that was displayed before an undo. The snapshot is a clone
     * of the Bundle, so the entry object is not part of it; it is found again by position and
     * label. An entry that the undo removed leaves the whole Bundle on screen, which is where
     * {@link #display} puts the user.
     */
    private void restoreDisplayedEntry(LoadedResource restored, int index, String displayName) {
        for (BundleEntryInfo candidate
                : flattenEntries(fhirService.bundleEntries(restored.getResource()))) {
            if (candidate.index() == index
                    && candidate.displayName().equals(displayName)
                    && candidate.hasResource()) {
                displayBundleEntry(candidate);
                return;
            }
        }
    }

    /**
     * Asks what to do about unsaved changes before an action replaces or closes the
     * loaded resource.
     *
     * @return {@code true} when the action may continue
     */
    private boolean confirmUnsavedChanges(String action) {
        if (loaded == null || !loaded.isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle("FHIR Resource Viewer");
        alert.setHeaderText("Unsaved changes");
        alert.setContentText("Save the changes to " + loaded.getDisplayName()
                + " before " + action + "?");
        themeDialog(alert.getDialogPane());

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) {
            return false;
        }
        if (choice.get() == save) {
            // Saving can still be cancelled (Save As), so only continue when it worked.
            return save() && loaded != null && !loaded.isDirty();
        }
        return true;
    }

    private void closeResource() {
        if (!confirmUnsavedChanges("closing the resource")) {
            return;
        }
        loaded = null;
        displayed = null;
        entry = null;
        label = "";
        selected = null;
        undo.clear();
        views.tree().show(null);
        views.pretty().showNothing();
        views.json().showNothing();
        views.xml().showNothing();
        views.fhirPath().recalculate();
        views.details().showNothingSelected();
        views.status().clearValidation();
        views.bundle().clear();
        status.accept(READY_STATUS);
        refreshTitle.run();
    }

    // ------------------------------------------------------------------
    // State the window's menu and title state reads
    // ------------------------------------------------------------------

    /** Whether there is an element selected in the tree. */
    boolean hasSelection() {
        return loaded != null && selected != null;
    }

    /** The selected node, or {@code null}. */
    ResourceNode selected() {
        return selected;
    }

    /** Whether a Bundle entry rather than the whole Bundle is displayed. */
    boolean showingEntry() {
        return entry != null;
    }

    /** Whether an undo is available. */
    boolean canUndo() {
        return !undo.isEmpty();
    }

    /** Whether the loaded document has unsaved changes. */
    boolean isDirty() {
        return loaded != null && loaded.isDirty();
    }

    /** The resource the tree and the document tabs currently show, or {@code null}. */
    IBaseResource currentTarget() {
        return loaded == null ? null : displayed;
    }

    /**
     * The reference target inside an entered value. The tree renders a reference as
     * <code>Type/id (display)</code>, so a trailing display is dropped before the value is
     * written; a plain <code>Type/id</code> or an absolute URL is used as entered.
     */
    private static String referenceValue(String text) {
        String value = text == null ? "" : text.trim();
        int display = value.indexOf(" (");
        return display > 0 ? value.substring(0, display).trim() : value;
    }

    /** Gives a dialog the same stylesheets as the main window, so it follows the theme. */
    private void themeDialog(DialogPane pane) {
        pane.getStylesheets().addAll(themeManager.stylesheets());
        pane.setPrefWidth(620);
    }
}
