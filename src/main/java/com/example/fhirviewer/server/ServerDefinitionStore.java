package com.example.fhirviewer.server;

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

import com.example.fhirviewer.util.FileSupport;

/**
 * Reads and writes the list of configured FHIR servers, so a server does not have to be
 * typed again after a restart.
 *
 * <p>The file is a properties file with one numbered block per server, plus the name of
 * the active one:</p>
 * <pre>
 * server.1.name=Local HAPI
 * server.1.baseUrl=http://localhost:8080/fhir
 * server.1.fhirVersion=R4
 * server.1.pluginId=standard-rest
 * server.1.timeoutMillis=0
 * active=Local HAPI
 * </pre>
 *
 * <p><b>No secret is ever written here.</b> A user name or password belongs to the
 * plugin that serves the server and lives in {@link PluginSettingsStore}, encrypted under
 * {@link SecretBox}; a server definition is a name, a URL and the plugin to use. Keeping
 * the two files apart is what makes it safe to hand this one to a support request.</p>
 *
 * <p>{@link FhirServerConfiguration#extraHeaders()} is not stored either: an extra header
 * name is not a secret but its value is, and {@link ServerDefinition} has no way to carry
 * one. A configuration that needs them is added again by the plugin manager rather than
 * silently losing its authentication here.</p>
 *
 * <p>Writes go through a temporary file and are then moved into place, the same way
 * {@link PluginSettingsStore} does it, so an interrupted save cannot leave a half-written
 * list behind. Reading never throws for a bad entry: a definition that no longer parses is
 * skipped, because one unreadable server should not cost the user the others.</p>
 */
public final class ServerDefinitionStore {

    /** Key prefix of a numbered server block. */
    private static final String BLOCK_PREFIX = "server.";

    /** The key naming the active server. */
    static final String ACTIVE_KEY = "active";

    private final Path file;

    /**
     * @param file where the definitions live; parent directories are created on write
     */
    public ServerDefinitionStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** The file this store reads and writes. */
    public Path file() {
        return file;
    }

    /** True when a definitions file exists. */
    public boolean exists() {
        return Files.isReadable(file);
    }

    /**
     * Reads the saved definitions, in the order they were written.
     *
     * <p>An entry that cannot be built - a base URL that is no longer a URL, a missing
     * name - is skipped rather than reported, so a hand-edited file with one bad line
     * still yields the servers around it.</p>
     *
     * @return the definitions, never {@code null}; empty when the file does not exist
     * @throws IOException when the file exists but cannot be read
     */
    public List<ServerDefinition> read() throws IOException {
        return parse(readProperties());
    }

    /**
     * Replaces the saved definitions with the given list.
     *
     * <p>A {@code null} active server is simply not written, which is the state of a
     * session in which no server has been selected.</p>
     */
    public void write(List<ServerDefinition> definitions, FhirServerConfiguration active)
            throws IOException {
        writeProperties(render(definitions, active));
    }

    /**
     * Builds the definitions from a parsed properties file.
     *
     * <p>Blocks are visited in index order, so the order the user arranged the servers in
     * is the order they come back in. Package-private and separate from the file access
     * so the interesting half - what a hand-edited file turns into - can be tested
     * without writing anything to disk.</p>
     */
    static List<ServerDefinition> parse(Properties properties) {
        List<ServerDefinition> definitions = new ArrayList<>();
        for (String index : blockIndices(properties)) {
            ServerDefinition definition = definitionOf(properties, BLOCK_PREFIX + index + ".");
            if (definition != null) {
                definitions.add(definition);
            }
        }
        return List.copyOf(definitions);
    }

    /**
     * Builds one definition, or {@code null} when the entry cannot be used.
     *
     * <p>A missing name or an unusable base URL is the only reason to drop a block. The
     * remaining fields have builder defaults, so an entry written by an older version
     * still loads rather than being discarded for a key it never had.</p>
     */
    private static ServerDefinition definitionOf(Properties properties, String key) {
        String name = trimmed(properties.getProperty(key + "name"));
        String baseUrl = trimmed(properties.getProperty(key + "baseUrl"));
        if (name == null || baseUrl == null) {
            return null;
        }
        ServerDefinition.Builder builder = ServerDefinition.named(name, baseUrl);
        String fhirVersion = trimmed(properties.getProperty(key + "fhirVersion"));
        if (fhirVersion != null) {
            builder.fhirVersion(fhirVersion);
        }
        String pluginId = trimmed(properties.getProperty(key + "pluginId"));
        if (pluginId != null) {
            builder.pluginId(pluginId);
        }
        return builder.timeoutMillis(timeoutOf(properties, key)).build();
    }

    /** The saved timeout, or the builder default when it is absent or not a number. */
    private static int timeoutOf(Properties properties, String key) {
        String raw = trimmed(properties.getProperty(key + "timeoutMillis"));
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The block indices present in the file, in ascending numeric order.
     *
     * <p>Only indices made purely of digits count, so a key such as
     * {@code server.baseUrl} written by something else cannot be mistaken for a block and
     * read as a server named after a URL. Sorting is numeric rather than textual because
     * {@code Properties} is itself unordered: blocks 1, 2 and 10 must not come back as
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
     * Renders definitions and the active selection as a properties object.
     *
     * <p>Written in full rather than merged into whatever is already on disk, so a server
     * the user removed is actually removed instead of lingering in the file forever.</p>
     */
    static Properties render(List<ServerDefinition> definitions, FhirServerConfiguration active) {
        Properties properties = new Properties();
        if (definitions != null) {
            int index = 1;
            for (ServerDefinition definition : definitions) {
                if (definition == null) {
                    continue;
                }
                String key = BLOCK_PREFIX + index++ + ".";
                properties.setProperty(key + "name", definition.name());
                properties.setProperty(key + "baseUrl", definition.baseUrl());
                properties.setProperty(key + "fhirVersion", definition.fhirVersion());
                properties.setProperty(key + "pluginId", definition.pluginId());
                properties.setProperty(key + "timeoutMillis",
                        Integer.toString(definition.timeoutMillis()));
            }
        }
        if (active != null && trimmed(active.name()) != null) {
            properties.setProperty(ACTIVE_KEY, active.name().trim());
        }
        return properties;
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
            properties.store(writer, "FHIRViewer FHIR server definitions. No credentials are stored here; "
                    + "passwords live encrypted in plugin-settings.properties.");
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
     * <p>Windows editors like to add one and a leading {@code U+FEFF} inside a value
     * would make a base URL unusable, so it goes through {@link FileSupport} rather than
     * being stripped only at the start of the file.</p>
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
        return "ServerDefinitionStore[" + file + "]";
    }
}
