package com.example.fhirviewer.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which {@link FhirServerPlugin} implementations the application should load.
 *
 * <p>Plugins reach the application through two independent routes, and this class
 * controls both:</p>
 * <ul>
 *   <li><b>Discovery</b> — service-file registration through
 *       {@code META-INF/services/...FhirServerPlugin} finds whatever the class path
 *       offers, including plugins shipped by third parties.</li>
 *   <li><b>Configuration</b> — {@link #enabledClasses()} names plugin classes to
 *       instantiate by name, which is how a plugin is loaded purely from config
 *       without being listed in the service file.</li>
 * </ul>
 *
 * <p>{@link #disabledClasses()} is a deny list applied to both routes, so an operator
 * can turn a plugin off without touching the class path or the service file.</p>
 *
 * <p>The file is a plain properties file. An empty or missing file is not an error:
 * it simply means "discover what is on the class path, enable nothing extra", which is
 * the behaviour of a build with no configuration at all.</p>
 */
public final class PluginConfig {

    /** The bundled default, read from the class path when nothing else is configured. */
    public static final String DEFAULT_RESOURCE = "/fhirviewer-plugins.properties";

    /** System property naming a properties file to load instead of the default. */
    public static final String CONFIG_PROPERTY = "fhirviewer.plugins.config";

    /** A file in the working directory that overrides the bundled default. */
    public static final String CONFIG_FILE_NAME = "fhirviewer-plugins.properties";

    /** Comma-separated plugin class names to instantiate by name. */
    public static final String ENABLED_KEY = "fhirviewer.plugins.enabled";

    /** Comma-separated plugin class names that must never be loaded. */
    public static final String DISABLED_KEY = "fhirviewer.plugins.disabled";

    private static final Logger log = LoggerFactory.getLogger(PluginConfig.class);

    private final Set<String> enabled;
    private final Set<String> disabled;
    private final String origin;

    private PluginConfig(Set<String> enabled, Set<String> disabled, String origin) {
        this.enabled = Set.copyOf(enabled);
        this.disabled = Set.copyOf(disabled);
        this.origin = origin;
    }

    /**
     * Resolves the configuration for a normal application run: the file named by
     * {@link #CONFIG_PROPERTY} if it is set, otherwise a {@link #CONFIG_FILE_NAME} in
     * the working directory if one exists, otherwise the bundled default.
     *
     * <p>Never throws: a configuration problem degrades to the bundled default and is
     * logged, because a missing or malformed plugin file must not stop the viewer
     * from starting.</p>
     */
    public static PluginConfig resolve() {
        String override = System.getProperty(CONFIG_PROPERTY);
        if (override != null && !override.isBlank()) {
            try {
                return load(Path.of(override.trim()));
            } catch (IOException | RuntimeException e) {
                log.warn("could not read the plugin config {}; using the bundled default", override, e);
                return defaults();
            }
        }
        Path local = Path.of(CONFIG_FILE_NAME);
        if (Files.isReadable(local)) {
            try {
                return load(local);
            } catch (IOException | RuntimeException e) {
                log.warn("could not read {}; using the bundled default", local, e);
                return defaults();
            }
        }
        return defaults();
    }

    /** The configuration shipped inside the application jar. */
    public static PluginConfig defaults() {
        try (InputStream in = PluginConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                log.debug("no bundled plugin config at {}; discovering the class path only",
                        DEFAULT_RESOURCE);
                return new PluginConfig(Set.of(), Set.of(), DEFAULT_RESOURCE + " (absent)");
            }
            return parse(in, DEFAULT_RESOURCE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the bundled plugin config", e);
        }
    }

    /** Reads a configuration from a properties file on disk. */
    public static PluginConfig load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.toString());
        }
    }

    /** Builds a configuration directly, for tests and programmatic callers. */
    public static PluginConfig of(Set<String> enabled, Set<String> disabled) {
        return new PluginConfig(enabled, disabled, "(programmatic)");
    }

    /**
     * Parses the two known keys out of a properties stream.
     *
     * <p>Values are comma-separated; blank entries and surrounding whitespace are
     * ignored, and lines beginning with {@code #} are comments. Unknown keys are
     * ignored so a newer configuration file still loads on an older build.</p>
     */
    private static PluginConfig parse(InputStream in, String origin) throws IOException {
        Properties properties = new Properties();
        properties.load(in);
        return new PluginConfig(
                split(properties.getProperty(ENABLED_KEY)),
                split(properties.getProperty(DISABLED_KEY)),
                origin);
    }

    private static Set<String> split(String value) {
        Set<String> values = new LinkedHashSet<>();
        if (value == null) {
            return values;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    /** Plugin class names the configuration asks to be instantiated by name. */
    public Set<String> enabledClasses() {
        return enabled;
    }

    /** Plugin class names that must not be loaded, whatever their source. */
    public Set<String> disabledClasses() {
        return disabled;
    }

    /** Where this configuration came from, for diagnostics and error messages. */
    public String origin() {
        return origin;
    }

    /** True when a class with this name is allowed to be loaded. */
    public boolean allows(String className) {
        return className != null && !disabled.contains(className);
    }

    @Override
    public String toString() {
        return "PluginConfig[from=" + origin
                + ", enabled=" + enabled
                + ", disabled=" + disabled + "]";
    }
}
