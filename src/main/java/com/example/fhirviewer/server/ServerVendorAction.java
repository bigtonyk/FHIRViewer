package com.example.fhirviewer.server;

/**
 * A server-specific screen a plugin offers to the user, described as data.
 *
 * <p>This is the seam for vendor tooling: a plugin that can do more than plain FHIR REST
 * (a Firely admin console, a Smile CDR operations screen, an export service) declares what
 * it offers here, and the main UI decides how to present it. Keeping this a plain record
 * rather than a JavaFX {@code Node} means the {@code server} package stays free of JavaFX and
 * usable from any thread, and a plugin never has to construct UI it does not own.</p>
 *
 * <p>The action id is the stable key the UI uses to find the implementation again; the
 * plugin resolves the id back to its own screen through
 * {@link FhirServerPlugin#openVendorTool(String, ServerSession, Object)}.</p>
 *
 * @param id          a stable identifier, unique within the plugin, for example {@code export}
 * @param label       the menu or button text shown to the user
 * @param description one sentence explaining what the screen does
 * @param enabledHint {@code null} when the screen can never be used, otherwise a short reason
 *                    it is currently unavailable (for example a missing capability)
 */
public record ServerVendorAction(
        String id,
        String label,
        String description,
        String enabledHint) {

    public ServerVendorAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A vendor action id is required.");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("A vendor action label is required.");
        }
        description = description == null ? "" : description;
    }

    /** A vendor action that is available now. */
    public static ServerVendorAction available(String id, String label, String description) {
        return new ServerVendorAction(id, label, description, null);
    }

    /** A vendor action that is declared but currently unusable, with the reason why. */
    public static ServerVendorAction unavailable(String id, String label, String description, String reason) {
        return new ServerVendorAction(id, label, description, reason);
    }

    /** True when the user can open this action right now. */
    public boolean isAvailable() {
        return enabledHint == null || enabledHint.isBlank();
    }
}
