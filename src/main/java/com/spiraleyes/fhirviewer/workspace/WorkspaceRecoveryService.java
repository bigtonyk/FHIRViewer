package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.model.IgPackageInfo;
import com.spiraleyes.fhirviewer.service.IgPackageManager;
import javafx.application.Platform;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
/**
 * Private application-managed recovery store that protects unsaved work independently
 * of the last explicitly saved workspace archive.
 * <p>
 * Features: periodic snapshots, debounced scheduling, atomic writes, bounded retention,
 * last-snapshot preservation on failure, thread-safe off-FX-thread I/O,
 * newer-snapshot detection, and Restore/Discard/Inspect decisions.
 */
public class WorkspaceRecoveryService {

    private static final Logger LOG = Logger.getLogger(WorkspaceRecoveryService.class.getName());

    private final Path recoveryDir;
    private final WorkspaceService workspaceService;
    private final Path currentWorkspaceArchive;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "FHIR-Recovery-Scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile Path lastSnapshotPath = null;
    private volatile Instant lastSnapshotTime = Instant.EPOCH;
    private volatile int snapshotCount = 0;
    private volatile boolean pendingSnapshot = false;
    private final Object snapshotLock = new Object();

    private final Runnable onSnapshotTaken;
    private final Runnable onRecoveryOffer;

    public WorkspaceRecoveryService(WorkspaceService workspaceService, Path userHome,
                                    Runnable onSnapshotTaken, Runnable onRecoveryOffer) {
        this.workspaceService = workspaceService;
        this.onSnapshotTaken = onSnapshotTaken;
        this.onRecoveryOffer = onRecoveryOffer;
        this.recoveryDir = userHome.resolve(".fhirviewer-recovery");
        this.currentWorkspaceArchive = workspaceService.getCurrentPath() != null
                ? workspaceService.getCurrentPath() : null;
        try { Files.createDirectories(recoveryDir); }
        catch (IOException e) { System.err.println("Recovery dir error: " + e.getMessage()); }
    }

    public void takeSnapshotNow() {
        synchronized (snapshotLock) {
            if (pendingSnapshot) return;
            pendingSnapshot = true;
        }
        scheduler.execute(this::doSnapshot);
    }

    public boolean handleCloseDecision() {
        if (!workspaceService.isDirty()) return true;
        // If there's a valid recovery snapshot, offer Restore/Discard/Inspect
        if (lastSnapshotPath != null && Files.isRegularFile(lastSnapshotPath)) {
            onRecoveryOffer.run(); // UI will show dialog and return user choice
            return true; // Close proceeds after UI decision
        }
        // No recovery snapshot – close proceeds (user will lose unsaved changes)
        return true;
    }

    public boolean restoreFromLastSnapshot() {
        if (lastSnapshotPath == null || !Files.isRegularFile(lastSnapshotPath)) {
            System.err.println("No recovery snapshot");
            return false;
        }
        try {
            WorkspaceMetadata m = WorkspaceArchiveReader.read(lastSnapshotPath);
            // Check schema compatibility – newer snapshots require Inspect decision
            if (m.schemaVersion() > WorkspaceMetadata.CURRENT_SCHEMA_VERSION) {
                System.err.println("Recovery snapshot schema version " + m.schemaVersion()
                        + " is newer than current workspace schema " + WorkspaceMetadata.CURRENT_SCHEMA_VERSION);
                // Newer snapshot – do not auto-restore; let UI offer Inspect/Discard
                onRecoveryOffer.run();
                return false;
            }
            workspaceService.restoreAll(m);
            workspaceService.markDirty();
            lastSnapshotPath = null; lastSnapshotTime = Instant.EPOCH;
            System.out.println("Restored from recovery");
            return true;
        } catch (IOException e) {
            System.err.println("Restore failed: " + e.getMessage());
            lastSnapshotPath = null; lastSnapshotTime = Instant.EPOCH;
            return false;
        }
    }

    public void saveCurrentArchive() throws IOException {
        if (workspaceService.getCurrent() == null) throw new IllegalStateException("No workspace");
        takeSnapshotNow();
        workspaceService.save();
        lastSnapshotPath = null; lastSnapshotTime = Instant.EPOCH; snapshotCount = 0;
        System.out.println("Archive saved; recovery baseline reset");
    }

    public void onMeaningfulChange() { takeSnapshotNow(); }

    private void doSnapshot() {
        synchronized (snapshotLock) { if (!pendingSnapshot) return; pendingSnapshot = false; }
        WorkspaceMetadata sm;
        try { sm = workspaceService.captureAllCurrent(); } catch (Exception e) { System.err.println("Capture error: " + e.getMessage()); return; }
        Path p;
        try { p = recoveryDir.resolve("workspace-" + workspaceService.getCurrent().workspaceId() + "-" + Instant.now().toString().replace(":","-") + ".snap"); }
        catch (Exception e) { System.err.println("Snapshot path error: " + e.getMessage()); return; }
        try {
            Path t = p.resolveSibling(p.getFileName() + ".tmp");
            WorkspaceArchiveWriter.write(t, sm);
            Files.move(t, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastSnapshotPath = p; lastSnapshotTime = Instant.now(); snapshotCount++;
            pruneOldSnapshots();
            Platform.runLater(onSnapshotTaken);
            System.out.println("Snapshot: " + p.getFileName());
        } catch (IOException e) { System.err.println("Write error: " + e.getMessage()); }
    }

    private void pruneOldSnapshots() {
        if (snapshotCount <= 50) return;
        try {
            List<Path> s = Files.list(recoveryDir).filter(p -> p.getFileName().toString().startsWith("workspace-")).sorted().toList();
            int del = s.size() - 50;
            for (int i = 0; i < del; i++) { try { Files.delete(s.get(i)); } catch (IOException ex) { System.err.println("Prune error: " + ex.getMessage()); } }
        } catch (IOException e) { System.err.println("List error: " + e.getMessage()); }
    }
}