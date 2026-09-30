package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * One server's saved connection settings: where it is and how to authenticate.
 *
 * <p>The base URL and user name are stored in the clear because they are not secret and
 * the user has to be able to read them. The password is never held here: the settings
 * file keeps it as an encrypted blob produced by {@link SecretBox}, and it is only turned
 * back into text in memory when the user supplies the passphrase for the session.</p>
 *
 * <p><b>Filed per server, not per plugin.</b> {@link #key()} is the server's own
 * {@link FhirServerConfiguration#credentialKey()}, because one plugin can serve several
 * servers and keying by plugin id made each save replace the previous one. The
 * {@link #pluginId()} is still carried, since the plugin manager groups and displays
 * settings by it.</p>
 *
 * <p>This class is deliberately the <i>unlocked</i> view of the settings. The locked view
 * — the encrypted text that actually lives in the file — is kept separately by
 * {@link PluginSettingsStore}.</p>
 */
public final class PluginSettings {

    private final String pluginId;
    private final String baseUrl;
    private final String userName;
    private final String password;
    private final String key;

    /**
     * @param pluginId the {@link FhirServerPlugin#id()} these settings belong to
     * @param baseUrl  the server's base URL, or {@code null} when not set yet
     * @param userName the user name, stored in the clear; may be {@code null}
     * @param password the decrypted password, or {@code null} for anonymous access;
     *                 never written to disk by this class
     */
    public PluginSettings(String pluginId, String baseUrl, String userName, String password) {
        this(pluginId, baseUrl, userName, password, pluginId);
    }

    /**
     * Settings filed under an explicit key rather than the plugin id.
     *
     * <p>Needed because one plugin can serve several servers. Keying only by plugin id meant
     * the second server's credentials replaced the first's, so a user who had configured
     * both correctly found one of them silently signing in as nobody. The key is the
     * server's own {@link FhirServerConfiguration#credentialKey()}; the plugin id is still
     * carried, because the plugin manager groups and displays settings by it.</p>
     *
     * @param key where these settings are filed; defaults to {@code pluginId} when absent
     */
    public PluginSettings(String pluginId, String baseUrl, String userName, String password,
            String key) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.baseUrl = baseUrl;
        this.userName = userName;
        this.password = password;
        this.key = key == null || key.isBlank() ? pluginId.trim() : key.trim();
    }

    /** Where these settings are filed, used as the prefix of every key in the file. */
    public String key() {
        return key;
    }

    /** The plugin these settings configure. */
    public String pluginId() {
        return pluginId;
    }

    /** The server's base URL, or {@code null}. */
    public String baseUrl() {
        return baseUrl;
    }

    /** The user name stored in the clear, or {@code null}. */
    public String userName() {
        return userName;
    }

    /**
     * The decrypted password held for this session only, or {@code null}.
     *
     * <p>Never log this value, and never pass it to a configuration object that might be
     * written to disk — the secret belongs on the {@link ServerSession}.</p>
     */
    public String password() {
        return password;
    }

    /** True when a user name or password has been supplied, i.e. auth is not anonymous. */
    public boolean hasCredentials() {
        return (userName != null && !userName.isBlank())
                || (password != null && !password.isEmpty());
    }

    /** A copy with a different base URL, keeping the credentials. */
    public PluginSettings withBaseUrl(String newBaseUrl) {
        return new PluginSettings(pluginId, newBaseUrl, userName, password);
    }

    /** A copy with different credentials, keeping the base URL. */
    public PluginSettings withCredentials(String newUserName, String newPassword) {
        return new PluginSettings(pluginId, baseUrl, newUserName, newPassword);
    }

    @Override
    public String toString() {
        // Never include the password: this object ends up in log lines and error messages.
        return "PluginSettings[plugin=" + pluginId
                + ", baseUrl=" + baseUrl
                + ", userName=" + userName
                + ", password=" + (password == null || password.isEmpty() ? "<none>" : "<set>")
                + "]";
    }
}
