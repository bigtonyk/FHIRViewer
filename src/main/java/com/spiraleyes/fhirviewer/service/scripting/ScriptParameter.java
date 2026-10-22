package com.spiraleyes.fhirviewer.service.scripting;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A named, typed parameter declared by a reusable script.
 */
public record ScriptParameter(
        String name,
        Class<?> type,
        Object defaultValue,
        boolean required) {

    public ScriptParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    public static ScriptParameter optional(String name, Class<?> type, Object defaultValue) {
        return new ScriptParameter(name, type, defaultValue, false);
    }

    public static ScriptParameter required(String name, Class<?> type) {
        return new ScriptParameter(name, type, null, true);
    }

    /** Validate a supplied value map against this parameter's contract. */
    public void validate(Map<String, Object> values) {
        Object v = values == null ? null : values.get(name);
        if (v == null) {
            if (required && defaultValue == null) throw new IllegalArgumentException("Missing required parameter: " + name);
            return;
        }
        if (!type.isInstance(v)) {
            throw new IllegalArgumentException("Parameter '" + name + "' expected " + type.getSimpleName()
                    + " but got " + v.getClass().getSimpleName());
        }
    }

    public Object resolve(Map<String, Object> values) {
        Object v = values == null ? null : values.get(name);
        if (v == null) {
            if (required && defaultValue == null) throw new IllegalArgumentException("Missing required parameter: " + name);
            return defaultValue;
        }
        if (!type.isInstance(v)) {
            throw new IllegalArgumentException("Parameter '" + name + "' expected " + type.getSimpleName()
                    + " but got " + v.getClass().getSimpleName());
        }
        return v;
    }

    /** Redact sensitive-looking values for reporting. */
    public static Object redact(String name, Object value) {
        if (value == null) return null;
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.contains("token") || lower.contains("secret") || lower.contains("password")
                || lower.contains("credential")) {
            return "[REDACTED]";
        }
        return value;
    }
}
