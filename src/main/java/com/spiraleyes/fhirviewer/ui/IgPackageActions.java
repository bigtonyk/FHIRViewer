package com.spiraleyes.fhirviewer.ui;

import java.util.function.BiConsumer;

import com.spiraleyes.fhirviewer.service.IgPackageManager;
import com.spiraleyes.fhirviewer.service.PackageRegistryService;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;

/**
 * The Implementation Guide menu: opening the package manager and listing what is installed.
 *
 * <p>Extracted from {@code MainWindow} as the second step of the M1 split (R4 §7). It needs the
 * package manager, the stage the dialog is modal to, and three decisions the window owns: how
 * to show an information alert, how to report a failure, and what to do once the installed set
 * may have changed — the profile picker is always refreshed, the workspace marked dirty only
 * when the count actually moved.</p>
 */
final class IgPackageActions {

    private final Stage owner;
    private final IgPackageManager packages;
    /** Shows an information alert with the given title and text. */
    private final BiConsumer<String, String> info;
    /** Reports a failure in the status line and in an error alert. */
    private final BiConsumer<String, Throwable> failure;
    /** Refreshes the profile choices, which are built from the active packages. */
    private final Runnable profileChoicesChanged;
    /** Marks the workspace dirty; the installed set is part of what it captures. */
    private final Runnable workspaceChanged;

    IgPackageActions(Stage owner, IgPackageManager packages, BiConsumer<String, String> info,
            BiConsumer<String, Throwable> failure, Runnable profileChoicesChanged,
            Runnable workspaceChanged) {
        this.owner = owner;
        this.packages = packages;
        this.info = info;
        this.failure = failure;
        this.profileChoicesChanged = profileChoicesChanged;
        this.workspaceChanged = workspaceChanged;
    }

    /** The Implementation Guide menu. */
    Menu menu() {
        MenuItem managePackages = new MenuItem("Manage Packages...");
        managePackages.setOnAction(event -> openPackageManager());

        MenuItem loadedPackages = new MenuItem("View Loaded Packages");
        loadedPackages.setOnAction(event -> showLoadedPackages());

        Menu menu = new Menu("Implementation Guide");
        menu.getItems().addAll(managePackages, new SeparatorMenuItem(), loadedPackages);
        return menu;
    }

    /** Opens the IG Package Manager dialog. */
    private void openPackageManager() {
        try {
            int installedBefore = packages.getLoadedPackageCount();
            IgPackageDialog dialog = new IgPackageDialog(owner, packages,
                    new PackageRegistryService());
            dialog.showAndWait();
            // Packages may have been installed or activated: update the profile picker.
            profileChoicesChanged.run();
            if (packages.getLoadedPackageCount() != installedBefore) {
                workspaceChanged.run();
            }
        } catch (Exception e) {
            failure.accept("Failed to open IG Package Manager", e);
        }
    }

    /**
     * Shows the installed IG packages, marking the ones that are not used for validation.
     */
    private void showLoadedPackages() {
        var loaded = packages.getLoadedPackages();
        if (loaded.isEmpty()) {
            info.accept("Loaded Packages",
                    "No Implementation Guide packages are currently loaded.\n\n"
                            + "To load packages:\n"
                            + "1. Go to Implementation Guide > Manage Packages\n"
                            + "2. Search for packages or load from file\n"
                            + "3. Download and load the desired packages");
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("Loaded Implementation Guide Packages\n");
        message.append("=====================================\n\n");
        for (var pkg : loaded) {
            message.append("- ").append(pkg.name()).append(' ').append(pkg.version())
                    .append(" (").append(pkg.fhirVersion()).append(')');
            if (!packages.isActive(pkg.name())) {
                message.append(" - inactive");
            }
            message.append('\n');
        }
        message.append("\nThese packages are available for validation.");
        info.accept("Loaded Packages", message.toString());
    }
}
