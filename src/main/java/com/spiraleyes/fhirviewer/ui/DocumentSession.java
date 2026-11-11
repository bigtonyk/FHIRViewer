package com.spiraleyes.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ResourceFormat;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.ResourceLoadException;
import com.spiraleyes.fhirviewer.util.FileSupport;

import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * The file-level document operations: open a resource, open a sample, save, save as, export.
 *
 * <p>The first half of M1's fifth step (R4 §7). The editor core — {@code display},
 * {@code refreshAfterEdit}, the undo stack and {@code closeResource} — deliberately stays in
 * the window: it owns nine views, the dirty flag and the tree rebuild order, and R4 asks for a
 * launched session before that moves. What is here needs none of it, because the document
 * itself is reached through {@link DocumentPort}.</p>
 *
 * <p>Behaviour is unchanged. The file dialogs remember the last directory across calls the way
 * they did in the window, a failed load is still reported through the same error dialog, and a
 * save still marks the document clean and refreshes the title, so the asterisk keeps matching
 * the file.</p>
 */
final class DocumentSession {

    private final Stage owner;
    private final FhirService fhirService;
    private final Consumer<String> status;
    private final BiConsumer<String, Throwable> failure;
    private final Runnable refreshTitle;
    private final DocumentPort document;

    /** The directory the last file dialog used, so the next one opens there. */
    private File lastDirectory;

    DocumentSession(Stage owner, FhirService fhirService, Consumer<String> status,
            BiConsumer<String, Throwable> failure, Runnable refreshTitle, DocumentPort document) {
        this.owner = owner;
        this.fhirService = fhirService;
        this.status = status;
        this.failure = failure;
        this.refreshTitle = refreshTitle;
        this.document = document;
    }

    void openFile() {
        if (!document.confirmDiscard("opening another resource")) {
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
                () -> document.show(fhirService.openFile(path), null));
    }

    void openSample(String classpathResource) {
        if (!document.confirmDiscard("opening a sample")) {
            return;
        }
        runWithWaitCursor("Loading sample " + classpathResource + " ...",
                () -> document.show(fhirService.openSample(classpathResource), null));
    }

    /** Saves the loaded resource, asking for a file when it does not have one yet. */
    boolean save() {
        LoadedResource loaded = document.loaded();
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
        LoadedResource loaded = document.loaded();
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

    /**
     * Writes the whole loaded resource (a Bundle included) to a file and marks it as
     * saved.
     */
    private boolean write(Path path, ResourceFormat format) {
        LoadedResource loaded = document.loaded();
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
        IBaseResource resource = document.resource();
        if (resource == null) {
            status.accept("Nothing to export. Open a FHIR resource first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + format.getDisplayName());
        chooser.setInitialFileName(suggestedFileName(resource, format));
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
            FileSupport.writeText(file.toPath(), fhirService.serialize(resource, format));
            status.accept("Exported " + document.label() + " to " + file.getAbsolutePath());
        } catch (IOException e) {
            failure.accept("Could not write " + file, e);
        }
    }

    /** Names the file after the resource type and id, as the save dialogs always have. */
    private String suggestedFileName(IBaseResource resource, ResourceFormat format) {
        String type = resource.fhirType();
        IIdType idElement = resource.getIdElement();
        String id = idElement != null && idElement.hasIdPart() ? idElement.getIdPart() : null;
        return (id == null ? type : type + "-" + id) + "." + format.getExtension();
    }

    private void runWithWaitCursor(String statusMessage, Runnable action) {
        status.accept(statusMessage);
        document.busy(true);
        try {
            action.run();
        } catch (ResourceLoadException e) {
            failure.accept("Could not load the resource", e);
        } finally {
            document.busy(false);
        }
    }
}
