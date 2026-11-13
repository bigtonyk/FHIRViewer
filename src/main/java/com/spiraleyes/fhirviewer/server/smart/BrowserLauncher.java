package com.spiraleyes.fhirviewer.server.smart;

import java.net.URI;

/**
 * Opens the authorization URL in the system browser.
 *
 * <p>A narrow interface rather than a direct {@code Desktop.browse} call, so
 * the authorization flow can be tested without opening a browser and so a
 * headless machine gets a reportable outcome instead of an exception. The
 * production implementation uses {@code java.awt.Desktop}; the workbench
 * falls back to showing the URL as copyable text when the desktop reports
 * the browser unavailable.</p>
 */
public interface BrowserLauncher {

    /**
     * @param url the authorization URL to open; never {@code null}
     * @return the outcome: opened, or why not
     */
    LaunchResult openAuthorizationUrl(URI url);

    /** Whether the browser opened or why it did not. */
    sealed interface LaunchResult permits LaunchResult.Opened, LaunchResult.Unavailable {

        /** The browser was asked to open the URL. */
        record Opened() implements LaunchResult {
        }

        /** The browser could not be used; the message says why. */
        record Unavailable(String message) implements LaunchResult {
        }
    }
}
