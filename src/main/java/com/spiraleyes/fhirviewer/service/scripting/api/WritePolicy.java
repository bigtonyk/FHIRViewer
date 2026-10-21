package com.spiraleyes.fhirviewer.service.scripting.api;

/**
 * Whether scripts may change state on a FHIR server.
 *
 * <p>Read-only is the default: scripts can read and search without any
 * permission. Creating, updating or deleting a resource requires an explicitly
 * enabled policy, so a script can never modify a server by accident. The console
 * decides when (or whether) to enable writes, and can refuse or confirm first.
 *
 * <p>This is a policy check, not a sandbox: it makes writes opt-in and obvious.
 */
public final class WritePolicy {

    /** Writes are refused with a clear message. The safe default. */
    public static final WritePolicy READ_ONLY = new WritePolicy(false,
            "Writes are disabled. Enable the write policy explicitly before creating, updating or deleting resources.",
            null);

    /** Writes are permitted. Destructive operations are approved by default. */
    public static WritePolicy enabled() {
        return new WritePolicy(true, null, null);
    }

    /**
     * Writes are permitted, with the given handler consulted before any
     * destructive operation (DELETE, and, in later phases, bulk destructive
     * work and destructive bundles). Returning {@code false} cancels the
     * operation before any request is sent.
     */
    public static WritePolicy enabled(Confirmation confirmation) {
        return new WritePolicy(true, null, confirmation);
    }

    /**
     * A prompt the policy runs before a destructive operation. The summary
     * describes the operation; returning {@code false} cancels it.
     */
    @FunctionalInterface
    public interface Confirmation {
        boolean confirm(String summary);
    }

    private final boolean enabled;
    private final String reason;
    private final Confirmation confirmation;

    private WritePolicy(boolean enabled, String reason, Confirmation confirmation) {
        this.enabled = enabled;
        this.reason = reason;
        this.confirmation = confirmation;
    }

    /** True when create, update and delete are permitted. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Why writes are not permitted, or empty when they are. */
    public String reason() {
        return reason == null ? "" : reason;
    }

    /** The configured destructive-operation confirmation handler, or {@code null}. */
    public Confirmation confirmation() {
        return confirmation;
    }
    /** Throws {@link WriteDeniedException} when writes are not permitted. */
    public void requireWrite() {
        if (!enabled) {
            throw new WriteDeniedException(reason.isEmpty()
                    ? "Writes are disabled."
                    : reason);
        }
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

    @Override
    public String toString() {
        return "WritePolicy(enabled=" + enabled + (reason == null ? "" : ", reason=" + reason) + ")";
    }
}
