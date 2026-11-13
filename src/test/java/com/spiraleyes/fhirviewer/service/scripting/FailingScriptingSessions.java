package com.spiraleyes.fhirviewer.service.scripting;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinators whose session factory stops working after a chosen number of sessions.
 *
 * <p>The real factory only fails when JShell cannot start at all, which a test cannot
 * provoke reliably. A failing factory lets a suite test reach the path where cases have
 * already run and the next case's session cannot be created.</p>
 */
public final class FailingScriptingSessions {

    private FailingScriptingSessions() {
    }

    /**
     * A coordinator whose first session is real and whose next creation throws.
     *
     * @param reason the message the failing creation carries; never {@code null}
     */
    public static ScriptingCoordinator failAfterOneSession(String reason) {
        AtomicInteger created = new AtomicInteger();
        return new ScriptingCoordinator(() -> {
            if (created.incrementAndGet() > 1) {
                throw new IllegalStateException(reason);
            }
            return new ScriptingSession();
        });
    }
}
