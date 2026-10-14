package com.spiraleyes.fhirviewer.server.smart;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import com.spiraleyes.fhirviewer.util.FileSupport;

/**
 * Reads and writes the SMART launch configurations, so a launch does not have to be typed
 * again after a restart.
 *
 * <p>Deliberately the same shape as
 * {@link com.spiraleyes.fhirviewer.server.ServerDefinitionStore}: a properties file with one
 * numbered block per launch plus the name of the selected one, written through a temporary file
 * and an atomic {@code Files.move}, read tolerantly so one unreadable block does not cost the
 * user the rest. A second persistence mechanism for the same kind of data would be a second
 * thing to get wrong.</p>
 *
 * <p><b>No secret is ever written here.</b> A SMART client on the desktop is a public client
 * and has no client secret, and an access token belongs to a session rather than to a stored
 * launch — see {@link SmartLaunchConfig}, which has no such field by design. What is stored is
 * a name, an issuer, a client id and the access being asked for.</p>
 *
 * @param file where the configurations live; parent directories are created on write
 */
public final class SmartLaunchStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SmartLaunchStore.class);

    /** Key prefix of a numbered launch block. */
    private static final String BLOCK_PREFIX = "launch.";

    /** The key naming the selected configuration. */
    static final String SELECTED_KEY = "selected";

    private final Path file;

    public SmartLaunchStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** The file this store reads and writes. */
    public Path file() {
        return file;
    }

    /** True when a configurations file exists. */
    public boolean exists() {
        return Files.isReadable(file);
    }

    /**
     * Reads the saved configurations, in the order they were written.
     *
     * <p>A block that cannot be built is skipped rather than reported, so a hand-edited file
     * with one bad line still yields the launches around it.</p>
     *
     * @return the configurations, never {@code null}; empty when the file does not exist
     * @throws IOException when the file exists but cannot be read
     */
    public List<SmartLaunchConfig> read() throws IOException {
        return parse(readProperties());
    }

    /**
     * The name of the configuration that was selected when the list was last written.
     *
     * <p>Read separately from the configurations themselves, because the saved name may name
     * one that has since been deleted; the caller decides what to do about that.</p>
     *
     * @return the saved name, or {@code null} when none was saved
     * @throws IOException when the file exists but cannot be read
     */
    public String readSelectedName() throws IOException {
        return trimmed(readProperties().getProperty(SELECTED_KEY));
    }

    /**
     * Replaces the saved configurations with the given list.
     *
     * <p>Written in full rather than merged with what is already on disk, so a configuration
     * the user deleted is actually gone. A {@code null} selection is simply not written.</p>
     *
     * @throws IOException when the file cannot be written
     */
    public void write(List<SmartLaunchConfig> configs, String selectedName) throws IOException {
        writeProperties(render(configs, selectedName));
    }

    /**
     * Builds the configurations from a parsed properties file.
     *
     * <p>Package-private and separate from the file access so the interesting half — what a
     * hand-edited file turns into — can be tested without writing anything to disk.</p>
     */
    static List<SmartLaunchConfig> parse(Properties properties) {
        List<SmartLaunchConfig> configs = new ArrayList<>();
        for (String index : blockIndices(properties)) {
            SmartLaunchConfig config = configOf(properties, BLOCK_PREFIX + index + ".");
            if (config != null) {
                configs.add(config);
            }
        }
        return List.copyOf(configs);
    }

    /**
     * Builds one configuration, or {@code null} when the entry cannot be used.
     *
     * <p>A missing id, a missing name or a missing issuer is the reason to drop a block. The
     * remaining fields have builder defaults, so an entry written by an older version still
     * loads rather than being discarded for a key it never had.</p>
     */
    private static SmartLaunchConfig configOf(Properties properties, String key) {
        String id = trimmed(properties.getProperty(key + "id"));
        String name = trimmed(properties.getProperty(key + "name"));
        String issuer = trimmed(properties.getProperty(key + "issuer"));
        if (id == null || name == null || issuer == null) {
            return null;
        }
        try {
            return builderOf(properties, key)
                    .id(id)
                    .name(name)
                    .issuer(issuer)
                    .build();
        } catch (IllegalArgumentException unusable) {
            log.info("skipping unreadable SMART launch configuration at {}: {}",
                    key, unusable.getMessage());
            return null;
        }
    }

    /** The optional fields of a block, which is everything the builder does not insist on. */
    private static SmartLaunchConfig.Builder builderOf(Properties properties, String key) {
        SmartLaunchConfig.Builder builder = SmartLaunchConfig.builder();
        builder.mode(SmartLaunchMode.fromName(properties.getProperty(key + "mode")));
        String clientId = trimmed(properties.getProperty(key + "clientId"));
        if (clientId != null) {
            builder.clientId(clientId);
        }
        String redirectUri = trimmed(properties.getProperty(key + "redirectUri"));
        if (redirectUri != null) {
            builder.redirectUri(redirectUri);
        }
        String scopes = trimmed(properties.getProperty(key + "scopes"));
        if (scopes != null) {
            builder.scopes(scopes);
        }
        String launchParameter = trimmed(properties.getProperty(key + "launch"));
        if (launchParameter != null) {
            builder.launchParameter(launchParameter);
        }
        String audience = trimmed(properties.getProperty(key + "audience"));
        if (audience != null) {
            builder.audience(audience);
        }
        return builder;
    }

    /**
     * The block indices present in the file, in ascending numeric order.
     *
     * <p>Only indices made purely of digits count, and the sort is numeric rather than textual
     * because {@code Properties} is itself unordered: blocks 1, 2 and 10 must not come back as
     * 1, 10, 2.</p>
     */
    private static Set<String> blockIndices(Properties properties) {
        Set<String> found = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(BLOCK_PREFIX)) {
                continue;
            }
            int dot = key.indexOf('.', BLOCK_PREFIX.length());
            if (dot <= BLOCK_PREFIX.length()) {
                continue;
            }
            String index = key.substring(BLOCK_PREFIX.length(), dot);
            if (!index.isEmpty() && index.chars().allMatch(Character::isDigit)) {
                found.add(index);
            }
        }
        List<String> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparingInt(Integer::parseInt));
        return new LinkedHashSet<>(ordered);
    }

    /**
     * Renders the configurations and the selection as a properties object.
     *
     * <p>An optional field is written only when it is set. Omitting it rather than writing an
     * empty value keeps a file written before the field existed byte-identical for the common
     * launch that does not use one.</p>
     */
    static Properties render(List<SmartLaunchConfig> configs, String selectedName) {
        Properties properties = new Properties();
        if (configs != null) {
            int index = 1;
            for (SmartLaunchConfig config : configs) {
                if (config == null) {
                    continue;
                }
                String key = BLOCK_PREFIX + index++ + ".";
                properties.setProperty(key + "id", config.id());
                properties.setProperty(key + "name", config.name());
                properties.setProperty(key + "issuer", config.issuer());
                properties.setProperty(key + "mode", config.mode().name());
                writeIfSet(properties, key + "clientId", config.clientId());
                writeIfSet(properties, key + "redirectUri", config.redirectUri());
                writeIfSet(properties, key + "scopes", config.scopes());
                writeIfSet(properties, key + "launch", config.launchParameter());
                writeIfSet(properties, key + "audience", config.audience());
            }
        }
        if (selectedName != null && trimmed(selectedName) != null) {
            properties.setProperty(SELECTED_KEY, selectedName.trim());
        }
        return properties;
    }

    private static void writeIfSet(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value.trim());
        }
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

    /** Writes through a temporary file so an interrupted save cannot corrupt the list. */
    private void writeProperties(Properties properties) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "FHIRViewer SMART launch configurations. No credentials are "
                    + "stored here; a SMART desktop client is a public client with no client "
                    + "secret, and access tokens live in memory for the session only.");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    /**
     * A stored value, trimmed, with any byte order mark removed.
     *
     * <p>Windows editors like to add one, and a leading {@code U+FEFF} inside a value would
     * make an issuer URL unusable, so it goes through {@link FileSupport} rather than being
     * stripped only at the start of the file.</p>
     *
     * @return the value, or {@code null} when it is absent or only whitespace
     */
    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String result = FileSupport.stripByteOrderMark(value).trim();
        return result.isEmpty() ? null : result;
    }

    @Override
    public String toString() {
        return "SmartLaunchStore[" + file + "]";
    }
}

