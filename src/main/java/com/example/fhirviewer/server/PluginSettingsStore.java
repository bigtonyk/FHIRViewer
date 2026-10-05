package com.example.fhirviewer.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Reads and writes saved connection settings, keeping passwords encrypted at rest.
 *
 * <p>The file is a properties file keyed by
 * {@link FhirServerConfiguration#credentialKey()}, which for a server this application
 * created is a generated id:</p>
 * <pre>
 * 3f1c...e7.baseUrl=https://firely.example.com/fhir
 * 3f1c...e7.userName=alice
 * 3f1c...e7.password=v1$&lt;salt&gt;$&lt;iv&gt;$&lt;ciphertext&gt;
 * firely.loadOnStart=true
 * </pre>
 *
 * <p><b>Credentials are keyed per server, not per plugin.</b> One plugin can serve
 * several servers, and keying by plugin id meant the second server's password replaced
 * the first's: both were configured correctly and one of them silently began signing
 * in as nobody, with nothing on screen to say why. A generated id also survives a
 * rename or a base-URL edit, which are the changes a user makes when a connection
 * is failing and exactly when the password is most needed. The id is shown
 * truncated above; the file holds it whole.</p>
 *
 * <p>An entry keyed by a plugin id is still read as a fallback, provided its saved
 * base URL matches, so a file written before per-server keys existed keeps
 * working. The URL check stays deliberately strict, because a credential must never
 * be sent to a host it was not saved for.</p>
 *
 * <p>The password is the only secret: it is encrypted with {@link SecretBox} under a
 * passphrase the user supplies. The base URL, user name and the load-on-start flag are
 * stored in the clear because they are not secret and the user has to be able to read and
 * edit them.</p>
 *
 * <p>Writes go to a temporary file and are then moved into place, so an interrupted save
 * cannot leave a half-written settings file behind.</p>
 */
public final class PluginSettingsStore {

    /** Suffix appended to a plugin id for each key. */
    private static final String BASE_URL_SUFFIX = ".baseUrl";
    private static final String USER_NAME_SUFFIX = ".userName";
    private static final String PASSWORD_SUFFIX = ".password";
    private static final String LOAD_ON_START_SUFFIX = ".loadOnStart";

    private final Path file;
    private final SecretBox secretBox = new SecretBox();

    /**
     * @param file where the settings live; parent directories are created on write
     */
    public PluginSettingsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** The file this store reads and writes. */
    public Path file() {
        return file;
    }

    /** True when a settings file exists. */
    public boolean exists() {
        return Files.isReadable(file);
    }

    /**
     * Reads every saved setting, keyed by plugin id.
     *
     * <p>Passwords come back as the encrypted text, not as plaintext: a caller without
     * the passphrase can list which plugins are configured and where they point, but
     * cannot recover the secrets. Use {@link #unlock} for the readable form.</p>
     */
    public Map<String, PluginSettings> readAll() throws IOException {
        Map<String, PluginSettings> settings = new LinkedHashMap<>();
        Properties properties = readProperties();
        for (String pluginId : pluginIdsIn(properties)) {
            settings.put(pluginId, new PluginSettings(
                    pluginId,
                    trimmed(properties.getProperty(pluginId + BASE_URL_SUFFIX)),
                    trimmed(properties.getProperty(pluginId + USER_NAME_SUFFIX)),
                    trimmed(properties.getProperty(pluginId + PASSWORD_SUFFIX))));
        }
        return settings;
    }

    /** The saved settings for one plugin, or {@code null} when it has none. */
    public PluginSettings read(String pluginId) throws IOException {
        if (pluginId == null || pluginId.isBlank()) {
            return null;
        }
        return readAll().get(pluginId.trim());
    }

    /**
     * The saved settings for the server a configuration points at, or {@code null} when
     * that server has none.
     *
     * <p>Settings are keyed by plugin id, so one entry describes one server per plugin.
     * Matching on the base URL as well means the entry is only used for the server it was
     * actually saved for: a user who points the same plugin at a second server, or who
     * changes the URL, gets anonymous access rather than a password sent somewhere it
     * was never meant to go. Sending a credential to the wrong host is not a recoverable
     * mistake, so the check is deliberately strict â€” trailing slashes and case in the
     * scheme and host are ignored, nothing else is.
     *
     * @param server the server the application is about to talk to
     */
    public PluginSettings readForServer(FhirServerConfiguration server) throws IOException {
        if (server == null) {
            return null;
        }
        Map<String, PluginSettings> all = readAll();
        PluginSettings keyed = all.get(credentialKeyOf(server));
        if (keyed != null) {
            return keyed;
        }
        // Nothing under the server's own key: accept a plugin-keyed entry only when its
        // saved base URL matches. That is how a file written before keys existed still
        // works, and the URL check stays deliberately strict, because a credential must
        // never be sent to a host it was not saved for.
        PluginSettings legacy = all.get(pluginIdOf(server));
        return describesSameServer(legacy, server) ? legacy : null;
    }

    /** The key a server's credentials are filed under, never blank. */
    private static String credentialKeyOf(FhirServerConfiguration server) {
        String key = server.credentialKey();
        return key == null || key.isBlank() ? pluginIdOf(server) : key.trim();
    }

    private static String pluginIdOf(FhirServerConfiguration server) {
        String id = server.pluginId();
        return id == null ? "" : id.trim();
    }

    /**
     * Decrypts the stored password for the server a configuration points at.
     *
     * @return the settings with a plaintext password, or {@code null} when that server
     *         has no saved settings
     * @throws SecretBoxException when the passphrase is wrong or the stored value is damaged
     */
    public PluginSettings unlockForServer(FhirServerConfiguration server, String passphrase)
            throws IOException, SecretBoxException {
        PluginSettings locked = readForServer(server);
        if (locked == null) {
            return null;
        }
        String stored = locked.password();
        if (stored == null || stored.isBlank()) {
            return locked.withCredentials(locked.userName(), null);
        }
        return locked.withCredentials(locked.userName(), secretBox.decrypt(passphrase, stored));
    }

    /**
     * True when a saved entry really describes this server.
     *
     * <p>An entry with no saved URL cannot be attributed to a server, so it is treated
     * as a match only when the server's own URL is also absent. In practice the plugin
     * manager always requires a URL, so the common path is an exact comparison.
     */
    private static boolean describesSameServer(PluginSettings settings,
            FhirServerConfiguration server) {
        if (settings == null) {
            return false;
        }
        String saved = settings.baseUrl();
        String wanted = server.baseUrl();
        if (saved == null || saved.isBlank()) {
            return wanted == null || wanted.isBlank();
        }
        if (wanted == null || wanted.isBlank()) {
            return false;
        }
        return normalizeUrl(saved).equals(normalizeUrl(wanted));
    }

    /**
     * Reduces a URL to the form two spellings of the same server share: lower case, and
     * no trailing slash. Only the scheme and host are case-insensitive in HTTP, but a
     * differing case anywhere is far more likely to be a different server than a
     * different spelling, and refusing to send credentials is the safe answer.
     */
    private static String normalizeUrl(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Decrypts the stored password for one plugin.
     *
     * @return the settings with a plaintext password, or {@code null} when the plugin
     *         has no saved settings
     * @throws SecretBoxException when the passphrase is wrong or the stored value is damaged
     */
    public PluginSettings unlock(String pluginId, String passphrase) throws IOException, SecretBoxException {
        PluginSettings locked = read(pluginId);
        if (locked == null) {
            return null;
        }
        String stored = locked.password();
        if (stored == null || stored.isBlank()) {
            return locked.withCredentials(locked.userName(), null);
        }
        String password = secretBox.decrypt(passphrase, stored);
        return locked.withCredentials(locked.userName(), password);
    }

    /**
     * Saves settings under their own key, encrypting the password under the passphrase.
     *
     * <p>An empty password clears the stored secret, which is how a user goes back to
     * anonymous access.</p>
     *
     * <p>Filed under {@link PluginSettings#key()} rather than the plugin id, so two servers
     * served by one plugin each keep their own credentials.</p>
     */
    public void save(PluginSettings settings, String passphrase) throws IOException, SecretBoxException {
        Objects.requireNonNull(settings, "settings");
        Map<String, PluginSettings> all = readAll();
        String password = settings.password();
        String encrypted = password == null || password.isEmpty()
                ? null
                : secretBox.encrypt(passphrase, password);
        all.put(settings.key(), new PluginSettings(
                settings.pluginId(),
                settings.baseUrl(),
                settings.userName(),
                encrypted,
                settings.key()));
        writeAll(all);
    }

    /** Removes one plugin's settings entirely. */
    public void remove(String pluginId) throws IOException {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        String id = pluginId.trim();
        // Every key for the plugin has to go, not just the ones writeAll happens to
        // rewrite: leaving e.g. the load-on-start flag behind would keep the id in the
        // file and the plugin would come back as an empty ghost entry.
        Properties properties = readProperties();
        for (String key : List.copyOf(properties.stringPropertyNames())) {
            if (key.startsWith(id + ".")) {
                properties.remove(key);
            }
        }
        writeProperties(properties);
    }

    /** True when this plugin is marked to load automatically at start-up. */
    public boolean loadsOnStart(String pluginId) throws IOException {
        return Boolean.parseBoolean(loadOnStartOf(pluginId));
    }

    /** Marks or clears a plugin's load-on-start flag, leaving its other settings alone. */
    public void setLoadsOnStart(String pluginId, boolean loadOnStart) throws IOException {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        Properties properties = readProperties();
        properties.setProperty(pluginId.trim() + LOAD_ON_START_SUFFIX, Boolean.toString(loadOnStart));
        writeProperties(properties);
    }

    private String loadOnStartOf(String pluginId) throws IOException {
        if (pluginId == null || pluginId.isBlank()) {
            return null;
        }
        return readProperties().getProperty(pluginId.trim() + LOAD_ON_START_SUFFIX);
    }

    private void writeAll(Map<String, PluginSettings> all) throws IOException {
        Properties properties = readProperties();
        for (Map.Entry<String, PluginSettings> entry : all.entrySet()) {
            // The map key is authoritative: it is the key the entry was filed under, and
            // reusing entry.getKey() rather than settings.key() keeps a hand-edited or
            // legacy entry writing back to the place it was read from.
            String id = entry.getKey();
            PluginSettings settings = entry.getValue();
            put(properties, id + BASE_URL_SUFFIX, settings.baseUrl());
            put(properties, id + USER_NAME_SUFFIX, settings.userName());
            put(properties, id + PASSWORD_SUFFIX, settings.password());
        }
        writeProperties(properties);
    }

    private static void put(Properties properties, String key, String value) {
        if (value == null || value.isBlank()) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    /** Derives the distinct plugin ids present in a properties file. */
    private static Set<String> pluginIdsIn(Properties properties) {
        Set<String> ids = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                ids.add(key.substring(0, dot));
            }
        }
        return ids;
    }

    private Properties readProperties() throws IOException {
        Properties properties = new Properties();
        if (Files.isReadable(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    /**
     * Writes through a temporary file so an interrupted save cannot corrupt the settings.
     */
    private void writeProperties(Properties properties) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "FHIRViewer plugin settings. Passwords are encrypted; "
                    + "the rest is plain text so it can be hand-edited.");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    @Override
    public String toString() {
        return "PluginSettingsStore[" + file + "]";
    }
}
