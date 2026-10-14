package com.spiraleyes.fhirviewer.server;

/**
 * Which SMART on FHIR launch a configuration is for.
 *
 * <p>SMART defines two ways a client reaches an authorized session, and they differ in
 * whether an <em>electronic health record system</em> hands the client a context first:</p>
 *
 * <ul>
 *   <li>{@link #STANDALONE} — the user picks the server and the client asks for access
 *       directly. Phase 00 §11 calls this the honest name for what FHIRViewer can do
 *       unaided.</li>
 *   <li>{@link #EHR_LAUNCH} — an EHR starts the application with a {@code launch} and an
 *       {@code iss} parameter and the client continues that context. Real EHR launch needs
 *       an inbound URI or a protocol handler, which Phase 05 addresses; the simulation mode
 *       here supplies the same parameters by hand so the rest of the flow can be built and
 *       tested against a real authorization server.</li>
 * </ul>
 *
 * <p>It lives in {@code server} rather than {@code ui} for the same reason
 * {@link ServerAuthKind} does: the mode decides which parameters belong in an
 * authorization request, which is a protocol question the form must not answer on its own.</p>
 */
public enum SmartLaunchMode {

    STANDALONE("Standalone launch"),
    EHR_LAUNCH("EHR launch (simulated)");

    private final String label;

    SmartLaunchMode(String label) {
        this.label = label;
    }

    /** The wording shown in the workbench, and in a stored file for a human reading it. */
    public String label() {
        return label;
    }

    /**
     * True when the launch continues a context an EHR supplied.
     *
     * <p>The single question the UI asks the mode: whether a launch parameter means
     * anything. Everything else the two modes differ in is downstream of this.</p>
     */
    public boolean needsLaunchParameter() {
        return this == EHR_LAUNCH;
    }

    /**
     * True when the client asks for access on its own behalf.
     *
     * <p>Only a standalone launch carries an {@code aud} (audience) request parameter; an
     * EHR launch gets its resource server from the {@code iss} it was launched from, and
     * sending {@code aud} as well is a request a server is entitled to refuse.</p>
     */
    public boolean allowsAudience() {
        return this == STANDALONE;
    }

    /**
     * Reads a mode from a stored name, falling back to {@link #STANDALONE}.
     *
     * <p>Never throws. A hand-edited settings file with a mode this build does not know
     * still has to load, and standalone is the mode that asks the least of a server.</p>
     */
    public static SmartLaunchMode fromName(String name) {
        if (name != null && !name.isBlank()) {
            for (SmartLaunchMode candidate : values()) {
                if (candidate.name().equalsIgnoreCase(name.trim())) {
                    return candidate;
                }
            }
        }
        return STANDALONE;
    }

    @Override
    public String toString() {
        return label;
    }
}
