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
            "Writes are disabled. Enable the write policy explicitly before creating, updating or deleting resources.");

    /** Writes are permitted. */
    public static WritePolicy enabled() {
        return new WritePolicy(true, null);
    }

    private final boolean enabled;
    private final String reason;

    private WritePolicy(boolean enabled, String reason) {
        this.enabled = enabled;
        this.reason = reason;
    }

    /** True when create, update and delete are permitted. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Why writes are not permitted, or empty when they are. */
    public String reason() {
        return reason == null ? "" : reason;
    }

    /** Throws {@link WriteDeniedException} when writes are not permitted. */
    public void requireWrite() {
        if (!enabled) {
            throw new WriteDeniedException(reason.isEmpty()
                    ? "Writes are disabled."
                    : reason);
        }
    }

    @Override
    public String toString() {
        return "WritePolicy(enabled=" + enabled + (reason == null ? "" : ", reason=" + reason) + ")";
    }
}
