package com.spiraleyes.fhirviewer.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Private application-managed recovery store that protects unsaved work independently
 * of the last explicitly saved workspace archive.
 * <p>
 * Features: snapshots coalesced onto one background thread, atomic writes, bounded
 * retention, and thread-safe I/O off the calling thread. Restore and Discard are offered
 * through {@link #hasRecoverySnapshot()} and {@link #restoreFromLastSnapshot()}, which
 * apply whatever the registered {@link WorkspaceStateProvider}s implement.
 */
public class WorkspaceRecoveryService {

    /**
     * What the user chose when asked about unsaved workspace state.
     *
     * <p>Two values, not three: saving and discarding both end with the close going
     * ahead, and only {@link #CANCEL} stops it — so the caller cannot accidentally
     * treat "Cancel" as a successful save the way the old {@code boolean} path did.</p>
     */
    public enum CloseDecision {
        /** Close, open or replace the workspace. */
        PROCEED,
        /** Keep the window open: the user cancelled, or a save they asked for failed. */
        CANCEL
    }

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceRecoveryService.class);

    /** How many snapshots the recovery directory keeps; the oldest are pruned first. */
    private static final int MAX_SNAPSHOTS = 50;

    private final Path recoveryDir;
    private final WorkspaceService workspaceService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "FHIR-Recovery-Scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile Path lastSnapshotPath = null;
    private volatile boolean pendingSnapshot = false;
    /** Bumped by every baseline reset; a snapshot queued before one describes a dead state. */
    private long snapshotGeneration = 0;
    private long pendingGeneration = 0;
    private final Object snapshotLock = new Object();

    /**
     * Asks the user about unsaved workspace state and returns what they chose. A supplier
     * rather than a {@code Runnable}, because a callback that cannot answer makes
     * {@link #handleCloseDecision()} a method that always says yes.
     */
    private final Supplier<CloseDecision> onCloseDecision;

    public WorkspaceRecoveryService(WorkspaceService workspaceService, Path userHome,
                                    Supplier<CloseDecision> onCloseDecision) {
        this.workspaceService = workspaceService;
        this.onCloseDecision = onCloseDecision;
        this.recoveryDir = userHome.resolve(".fhirviewer-recovery");
        try {
            Files.createDirectories(recoveryDir);
        } catch (IOException e) {
            LOG.warn("Could not create the recovery directory {}: {}", recoveryDir, e.getMessage());
        }
    }

    public void takeSnapshotNow() {
        synchronized (snapshotLock) {
            if (pendingSnapshot) return;
            pendingSnapshot = true;
            pendingGeneration = snapshotGeneration;
        }
        try {
            scheduler.execute(this::doSnapshot);
        } catch (RejectedExecutionException e) {
            LOG.debug("Ignored a recovery snapshot requested after the service was closed");
            synchronized (snapshotLock) { pendingSnapshot = false; }
        }
    }

    /**
     * Asks whether a dirty workspace may be closed or replaced, and returns the answer.
     *
     * <p>A clean workspace proceeds without a prompt. A dirty one always asks, because
     * the alternative — closing, or opening another workspace over it, without asking —
     * is exactly the silent loss this class exists to prevent. The callback runs on the
     * caller's thread, which for the window close handler is the JavaFX thread.</p>
     *
     * @return {@code true} only when the user chose to proceed
     */
    public boolean handleCloseDecision() {
        if (!workspaceService.isDirty()) {
            return true;
        }
        CloseDecision decision = onCloseDecision.get();
        return decision == CloseDecision.PROCEED;
    }

    /** True when there is a snapshot {@link #restoreFromLastSnapshot()} could restore. */
    public boolean hasRecoverySnapshot() {
        Path snapshot = lastSnapshotPath;
        return snapshot != null && Files.isRegularFile(snapshot);
    }

    public boolean restoreFromLastSnapshot() {
        if (lastSnapshotPath == null || !Files.isRegularFile(lastSnapshotPath)) {
            LOG.debug("No recovery snapshot to restore");
            return false;
        }
        try {
            WorkspaceMetadata m = WorkspaceArchiveReader.read(lastSnapshotPath);
            // Check schema compatibility – newer snapshots require Inspect decision
            if (m.schemaVersion() > WorkspaceMetadata.CURRENT_SCHEMA_VERSION) {
                LOG.warn("Recovery snapshot schema version {} is newer than the current workspace schema {}",
                        m.schemaVersion(), WorkspaceMetadata.CURRENT_SCHEMA_VERSION);
                // Newer snapshot – do not auto-restore; the caller offers Inspect/Discard.
                return false;
            }
            workspaceService.restoreAll(m);
            workspaceService.markDirty();
            forgetSnapshot();
            LOG.info("Restored the workspace from a recovery snapshot");
            return true;
        } catch (IOException e) {
            LOG.warn("Could not restore the recovery snapshot: {}", e.getMessage());
            forgetSnapshot();
            return false;
        }
    }

    /**
     * Forgets the current snapshot, for a workspace that has just been replaced by another
     * one or saved: a snapshot of the previous state is not this workspace's history.
     */
    public void forgetSnapshot() {
        // Bumping the generation also disowns snapshots that are still queued or being
        // written: clearing the path alone let one finish afterwards and offer itself back.
        synchronized (snapshotLock) {
            snapshotGeneration++;
            lastSnapshotPath = null;
        }
    }

    /**
     * Writes the workspace archive and resets the recovery baseline once it is on disk.
     *
     * <p>The order matters: queueing a snapshot before the save left one that could finish
     * afterwards and set {@code lastSnapshotPath} again, so the next restore would offer a
     * snapshot of the state the save had just replaced.</p>
     */
    public void saveCurrentArchive() throws IOException {
        if (workspaceService.getCurrent() == null) throw new IllegalStateException("No workspace");
        workspaceService.save();
        forgetSnapshot();
        LOG.debug("Workspace archive saved; recovery baseline reset");
    }

    public void onMeaningfulChange() { takeSnapshotNow(); }

    private void doSnapshot() {
        long generation;
        synchronized (snapshotLock) {
            if (!pendingSnapshot) return;
            pendingSnapshot = false;
            generation = pendingGeneration;
            if (generation != snapshotGeneration) {
                LOG.debug("Skipped a recovery snapshot the workspace had already moved past");
                return;
            }
        }
        // Nothing to snapshot before a workspace exists: a session that has only ever had
        // its servers configured has no workspace id to name the file after, and capture
        // needs one. The change that asked for this snapshot is still marked dirty, so the
        // first Save creates the workspace the next snapshot belongs to.
        if (workspaceService.getCurrent() == null) {
            LOG.debug("Skipped a recovery snapshot: no workspace is open");
            return;
        }
        WorkspaceMetadata sm;
        try { sm = workspaceService.captureAllCurrent(); } catch (Exception e) { LOG.warn("Could not capture the workspace state: {}", e.getMessage()); return; }
        Path p;
        try { p = recoveryDir.resolve("workspace-" + workspaceService.getCurrent().workspaceId() + "-" + Instant.now().toString().replace(":", "-") + ".snap"); }
        catch (Exception e) { LOG.warn("Could not resolve the snapshot path: {}", e.getMessage()); return; }
        try {
            Path t = p.resolveSibling(p.getFileName() + ".tmp");
            WorkspaceArchiveWriter.write(t, sm);
            Files.move(t, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { LOG.warn("Could not write a recovery snapshot: {}", e.getMessage()); }
        synchronized (snapshotLock) {
            if (generation != snapshotGeneration) {
                // Saved or replaced while this was being written: the file describes a state
                // that is no longer anyone's recovery point.
                LOG.debug("Discarded a recovery snapshot the workspace moved past while it was written");
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) { LOG.warn("Could not remove the stale snapshot {}: {}", p.getFileName(), e.getMessage()); }
                return;
            }
            lastSnapshotPath = p;
        }
        pruneOldSnapshots();
        LOG.debug("Snapshot written: {}", p.getFileName());
    }

    private void pruneOldSnapshots() {
        // Retention counts the files on disk: an in-memory counter starts at zero on every
        // launch while the .snap files persist, so the cap only ever fired after 51 snapshots
        // in one session and the directory otherwise grew without limit.
        // Files.list owns a directory handle: closing the stream releases it, while the
        // unclosed stream below would leak one per prune for the life of the process.
        try (Stream<Path> entries = Files.list(recoveryDir)) {
            List<Path> stale = entries.filter(p -> p.getFileName().toString().startsWith("workspace-")).sorted().toList();
            int del = stale.size() - MAX_SNAPSHOTS;
            for (int i = 0; i < del; i++) { try { Files.delete(stale.get(i)); } catch (IOException ex) { LOG.warn("Could not prune {}: {}", stale.get(i), ex.getMessage()); } }
        } catch (IOException e) { LOG.warn("Could not read the recovery directory: {}", e.getMessage()); }
    }

    /**
     * Stops the snapshot thread, once the window that owns this service is gone.
     *
     * <p>Without it, every window closed and reopened left its own snapshot thread behind,
     * with no way to release it. Snapshots already queued are allowed to finish, so the
     * files on disk match what the service last reported; only a thread that will not stop
     * is interrupted. Idempotent: the window's close handler and its exit action can both
     * reach it.</p>
     */
    public void close() {
        // shutdown(), not shutdownNow(): a snapshot requested moments before the window
        // closed is still worth keeping, and cancelling the queued task would throw it away.
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                LOG.warn("The recovery snapshot thread did not stop within five seconds");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
