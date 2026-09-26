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
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Reads and writes each plugin's saved connection settings, keeping passwords encrypted at rest.
 *
 * <p>The file is a properties file keyed by plugin id:</p>
 * <pre>
 * firely.baseUrl=https://firely.example.com/fhir
 * firely.userName=alice
 * firely.password=v1$&lt;salt&gt;$&lt;iv&gt;$&lt;ciphertext&gt;
 * firely.loadOnStart=true
 * </pre>
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
     * Saves settings for one plugin, encrypting the password under the passphrase.
     *
     * <p>An empty password clears the stored secret, which is how a user goes back to
     * anonymous access.</p>
     */
    public void save(PluginSettings settings, String passphrase) throws IOException, SecretBoxException {
        Objects.requireNonNull(settings, "settings");
        Map<String, PluginSettings> all = readAll();
        String password = settings.password();
        if (password != null && !password.isEmpty()) {
            all.put(settings.pluginId(), new PluginSettings(
                    settings.pluginId(),
                    settings.baseUrl(),
                    settings.userName(),
                    secretBox.encrypt(passphrase, password)));
        } else {
            // Anonymous: keep the URL and user name, drop the secret entirely.
            all.put(settings.pluginId(), new PluginSettings(
                    settings.pluginId(), settings.baseUrl(), settings.userName(), null));
        }
        writeAll(all);
    }

    /** Removes one plugin's settings entirely. */
    public void remove(String pluginId) throws IOException {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        Map<String, PluginSettings> all = readAll();
        if (all.remove(pluginId.trim()) != null) {
            writeAll(all);
        }
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
