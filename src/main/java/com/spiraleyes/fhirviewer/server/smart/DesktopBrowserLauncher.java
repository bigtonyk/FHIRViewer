package com.spiraleyes.fhirviewer.server.smart;

import java.awt.Desktop;
import java.net.URI;
import java.util.Objects;

/**
 * Opens authorization URLs with the system default browser.
 *
 * <p>Used through the {@link BrowserLauncher} interface so the flow never
 * depends on this class directly: a headless or restricted desktop reports
 * {@link BrowserLauncher.LaunchResult.Unavailable} rather than throwing, and
 * the workbench then offers the URL as text to copy.</p>
 */
public final class DesktopBrowserLauncher implements BrowserLauncher {

    @Override
    public LaunchResult openAuthorizationUrl(URI url) {
        Objects.requireNonNull(url, "url");
        if (!Desktop.isDesktopSupported()) {
            return new LaunchResult.Unavailable(
                    "This desktop does not support opening a browser. "
                            + "Copy the authorization URL instead.");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            return new LaunchResult.Unavailable(
                    "This desktop cannot open a browser window. "
                            + "Copy the authorization URL instead.");
        }
        try {
            desktop.browse(url);
        } catch (Exception e) {
            return new LaunchResult.Unavailable(
                    "The browser could not be opened (" + firstLine(e)
                            + "). Copy the authorization URL instead.");
        }
        return new LaunchResult.Opened();
    }

    private static String firstLine(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int at = message.indexOf('\n');
        return (at < 0 ? message : message.substring(0, at)).trim();
    }

    @Override
    public String toString() {
        return "DesktopBrowserLauncher";
    }
}
