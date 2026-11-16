package com.spiraleyes.fhirviewer.service.scripting.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;

/**
 * Whether scripts may change state on a FHIR server.
 *
 * <p>Read-only is the default: scripts can read and search without any
 * permission. Creating, updating, patching or deleting a resource requires an explicitly
 * enabled policy, so a script can never modify a server by accident. The console
 * enables it only after the user chooses to allow server writes for that console
 * session, and it can refuse or confirm a destructive operation first.
 *
 * <p>An enabled policy is a live grant, not a boolean snapshot. Turning it off
 * revokes every {@link FhirApi} that still holds it, and an enabled console grant
 * refuses writes to any server except the one named in its warning. This matters
 * because an interactive JShell session survives between runs: a script can keep
 * an old {@code fhir} reference in a variable, but it cannot use that reference
 * to write after the user has switched the grant off.
 *
 * <p>This is a policy check, not a sandbox: it makes writes opt-in and obvious.
 */
public final class WritePolicy {

    /** Writes are refused with a clear message. The safe default. */
    public static final WritePolicy READ_ONLY = new WritePolicy(false,
            "Writes are disabled. Enable the write policy explicitly before creating, updating, patching or deleting resources.",
            null, null, null, 0, null, null);

    /** Writes are permitted. Destructive operations are approved by default. */
    public static WritePolicy enabled() {
        return new WritePolicy(true, null, null, null, null, 0, null, null);
    }

    /**
     * Writes are permitted, with the given handler consulted before any
     * destructive operation (DELETE, and, in later phases, bulk destructive
     * work and destructive bundles). Returning {@code false} cancels the
     * operation before any request is sent.
     */
    public static WritePolicy enabled(Confirmation confirmation) {
        return new WritePolicy(true, null, confirmation, null, null, 0, null, null);
    }

    /**
     * A live console grant. It is bound to {@code target}, journals every attempt,
     * refuses after {@code writeBudget} attempts until the user approves another
     * batch, and expires after {@code idleTimeout} without a write.
     *
     * @param target          the one server this grant may write to; {@code null}
     *                        keeps the unbounded behavior used by API tests
     * @param journal         where attempts are recorded, or {@code null}
     * @param writeBudget     attempts per batch, or {@code 0} for no budget
     * @param budgetConfirmation consulted when a run reaches its budget
     * @param idleTimeout     how long the grant survives without a write; {@code null}
     *                        means it does not expire
     */
    public static WritePolicy enabled(Confirmation confirmation, FhirServerConfiguration target,
            WriteJournal journal, int writeBudget, BudgetConfirmation budgetConfirmation,
            Duration idleTimeout) {
        return new WritePolicy(true, null, confirmation, target, journal, writeBudget,
                budgetConfirmation, idleTimeout);
    }

    /**
     * A prompt the policy runs before a destructive operation. The summary
     * describes the operation; returning {@code false} cancels it.
     */
    @FunctionalInterface
    public interface Confirmation {
        boolean confirm(String summary);
    }

    /** A prompt shown when a run reaches its write budget. */
    @FunctionalInterface
    public interface BudgetConfirmation {
        /**
         * @param completedWrites writes already attempted in this batch
         * @param target          the server the grant is bound to
         * @return true to allow another batch, false to stop
         */
        boolean confirm(int completedWrites, String target);
    }

    private final AtomicBoolean enabled;
    private final String reason;
    private final Confirmation confirmation;
    private final FhirServerConfiguration target;
    private final WriteJournal journal;
    private final int writeBudget;
    private final BudgetConfirmation budgetConfirmation;
    private final Duration idleTimeout;
    private final AtomicLong expiresAtNanos = new AtomicLong();
    private final AtomicInteger writesThisRun = new AtomicInteger();
    private volatile String revokedReason;

    private WritePolicy(boolean enabled, String reason, Confirmation confirmation,
            FhirServerConfiguration target, WriteJournal journal, int writeBudget,
            BudgetConfirmation budgetConfirmation, Duration idleTimeout) {
        this.enabled = new AtomicBoolean(enabled);
        this.reason = reason;
        this.confirmation = confirmation;
        this.target = target;
        this.journal = journal;
        this.writeBudget = Math.max(0, writeBudget);
        this.budgetConfirmation = budgetConfirmation;
        this.idleTimeout = idleTimeout;
        touch();
    }

    /** True when create, update, patch and delete are permitted right now. */
    public boolean isEnabled() {
        return enabled.get() && !expired();
    }

    /** Why writes are not permitted, or empty when they are. */
    public String reason() {
        if (revokedReason != null) {
            return revokedReason;
        }
        if (!enabled.get()) {
            return reason == null ? "" : reason;
        }
        if (expired()) {
            return "Writes were turned off after inactivity. Enable them again to continue.";
        }
        return "";
    }

    /** Throws {@link WriteDeniedException} when writes are not permitted. */
    public void requireWrite() {
        if (!isEnabled()) {
            String message = reason();
            throw new WriteDeniedException(message.isEmpty() ? "Writes are disabled." : message);
        }
    }

    /**
     * Checks a write for a particular server, then charges one attempt against
     * the budget. A grant bound to a server refuses a different active server.
     */
    public void requireWrite(FhirServerConfiguration server, String operation) {
        requireWrite();
        if (target != null && !sameServer(server, target)) {
            throw new WriteDeniedException("Writes are approved for " + describe(target)
                    + ", not " + describe(server) + ". Turn writes off and on again to approve"
                    + " a different server.");
        }
        acquireBudget(server, operation);
    }

    /** Starts a new run: the budget resets and the idle timer is refreshed. */
    public void beginRun() {
        writesThisRun.set(0);
        touch();
    }

    /** Revokes this grant. Every reference that still holds it stops writing immediately. */
    public void revoke() {
        if (enabled.compareAndSet(true, false) && revokedReason == null) {
            revokedReason = "Writes were turned off.";
        }
    }

    /** Refreshes the idle deadline after a write or at the start of a run. */
    public void touch() {
        if (enabled.get() && idleTimeout != null) {
            expiresAtNanos.set(System.nanoTime() + idleTimeout.toNanos());
        }
    }

    /** True when the idle deadline has passed. */
    public boolean expired() {
        return enabled.get() && idleTimeout != null
                && System.nanoTime() >= expiresAtNanos.get();
    }

    /** The server this grant is bound to, or {@code null} for an unrestricted test policy. */
    public FhirServerConfiguration target() {
        return target;
    }

    /** The journal for this grant, or {@code null} when the caller did not supply one. */
    public WriteJournal journal() {
        return journal;
    }

    /** Writes attempted in the current budget batch, for tests and diagnostics. */
    public int writesThisRun() {
        return writesThisRun.get();
    }

    /**
     * Consults the destructive-operation confirmation handler. Returns
     * {@code true} when the operation may proceed. Throws
     * {@link WriteDeniedException} when writes are disabled.
     */
    public boolean confirmDestructive(String summary) {
        requireWrite();
        if (confirmation == null) {
            return true;
        }
        return confirmation.confirm(summary);
    }

    private void acquireBudget(FhirServerConfiguration server, String operation) {
        if (writeBudget <= 0) {
            return;
        }
        while (true) {
            int used = writesThisRun.get();
            if (used < writeBudget) {
                if (writesThisRun.compareAndSet(used, used + 1)) {
                    return;
                }
                continue;
            }
            boolean approved = budgetConfirmation != null
                    && budgetConfirmation.confirm(used, describe(server));
            if (!approved) {
                throw new WriteDeniedException("This run reached its " + writeBudget
                        + "-write budget. Enable writes again or raise the limit before"
                        + " attempting another " + operation + ".");
            }
            writesThisRun.set(0);
        }
    }

    private static boolean sameServer(FhirServerConfiguration left, FhirServerConfiguration right) {
        if (left == right) {
            return true;
        }
        return left != null && right != null
                && Objects.equals(left.pluginId(), right.pluginId())
                && Objects.equals(left.baseUrl(), right.baseUrl());
    }

    private static String describe(FhirServerConfiguration server) {
        return server == null ? "no active server"
                : server.name() + " (" + server.baseUrl() + ")";
    }

    @Override
    public String toString() {
        return "WritePolicy(enabled=" + isEnabled()
                + (target == null ? "" : ", target=" + describe(target))
                + (reason().isEmpty() ? "" : ", reason=" + reason()) + ")";
    }
}
