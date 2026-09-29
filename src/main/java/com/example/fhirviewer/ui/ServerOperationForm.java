package com.example.fhirviewer.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.example.fhirviewer.server.ServerOperation;
import com.example.fhirviewer.server.ServerOperationInvocation;
import com.example.fhirviewer.server.ServerOperationParameter;

/**
 * The input side of a discovered {@link ServerOperation}, as plain data.
 *
 * <p>This is the half of the generic operation screen that does not need JavaFX, and it is
 * the half worth testing: it turns a descriptor plus whatever the user typed into a
 * {@link ServerOperationInvocation}, or into a sentence explaining what is still missing.
 * The JavaFX screen next door only draws these fields and calls
 * {@link #toInvocation()}.</p>
 *
 * <p><b>Why it is not the plugin's job.</b> A plugin declares <em>what</em> an endpoint
 * needs; nothing in the application knows what any particular endpoint is for. A form
 * generated from {@link ServerOperation#parameters()} is therefore the only way this screen
 * can be correct for a plugin that does not exist yet — the list of fields is the plugin's
 * list, so adding an operation to a plugin adds a form with no change here.</p>
 *
 * <p><b>Validation happens before the request.</b> {@link #toInvocation()} refuses a call
 * that is missing a required parameter or a required body. That check is repeated by
 * {@link com.example.fhirviewer.server.PluginOperationClient} before it opens a socket, and
 * it is kept here as well on purpose: this one can tell the user which field is empty,
 * which is the only version of the message that is any use at a form.</p>
 *
 * <p>Values are held in insertion order and never sorted, so a query string a plugin
 * assembled deliberately reaches the server in the order it was written.</p>
 */
public final class ServerOperationForm {

    private final ServerOperation operation;
    private final Map<String, String> values = new LinkedHashMap<>();

    /** Builds an empty form for one operation. */
    public ServerOperationForm(ServerOperation operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /** The operation this form is for. */
    public ServerOperation operation() {
        return operation;
    }

    /** The declared parameters, in the order the plugin listed them. */
    public List<ServerOperationParameter> parameters() {
        return operation.parameters();
    }

    /**
     * Records a value for a declared parameter.
     *
     * <p>Blank is stored as blank rather than dropped, so a field the user cleared and one
     * they never touched are told apart by {@link #missing()}, which is what lets a form
     * say "Name is required" about the field that is actually empty in front of them.</p>
     *
     * @throws IllegalArgumentException when the operation never declared this name, which
     *         would otherwise be a typo silently changing the request that gets built
     */
    public ServerOperationForm set(String parameterName, String value) {
        Objects.requireNonNull(parameterName, "parameterName");
        if (operation.parameter(parameterName).isEmpty()) {
            throw new IllegalArgumentException("The operation '" + operation.id()
                    + "' does not declare a parameter called '" + parameterName + "'.");
        }
        values.put(parameterName, value == null ? "" : value);
        return this;
    }

    /** The value entered for a parameter, or the empty string. */
    public String value(String parameterName) {
        return values.getOrDefault(parameterName, "");
    }

    /**
     * The first required input that has no value, ignoring any body the user has typed.
     *
     * <p>Convenience for a caller that only wants to know which <em>fields</em> are empty —
     * a screen marking incomplete fields red before the body is considered. To decide
     * whether the operation can actually run, use {@link #missing(String)}, which is the
     * one that takes the body into account.</p>
     */
    public Optional<String> missing() {
        return missing(null);
    }

    /**
     * The first required input that has no value, or empty when the form is ready to run.
     *
     * <p>Ordered path, then query, then header, then the body, so the message names the field
     * most likely to be the one the user is looking at.</p>
     *
     * <p>The body is a parameter rather than a field for a reason worth stating: a form
     * cannot know whether a required body has been supplied by looking at its own inputs,
     * and a version of this that ignored the text would refuse to run every operation that
     * takes one.</p>
     *
     * @param body the request body the user has typed, or {@code null} when there is none
     */
    public Optional<String> missing(String body) {
        for (ServerOperationParameter.Location location : ServerOperationParameter.Location.values()) {
            for (ServerOperationParameter parameter : operation.parameters()) {
                if (parameter.location() == location
                        && parameter.isRequired()
                        && value(parameter.name()).isBlank()) {
                    return Optional.of(label(parameter));
                }
            }
        }
        if (operation.bodyRequirement() == ServerOperation.BodyRequirement.REQUIRED
                && (body == null || body.isBlank())) {
            return Optional.of("a request body");
        }
        return Optional.empty();
    }

    /**
     * Builds the invocation, or refuses and says what is missing.
     *
     * <p>Empty values are dropped rather than sent, so an untouched optional field does not
     * become {@code ?format=} on the wire. {@link ServerOperationInvocation.Builder} applies
     * the same rule, but doing it here means the request the user sees described and the
     * request that is sent are built from one set of values.</p>
     *
     * @throws IllegalStateException when a required input has no value, with a message
     *         naming the field so the dialog can point at it
     */
    public ServerOperationInvocation toInvocation(String body) {
        Optional<String> missing = missing(body);
        if (missing.isPresent()) {
            throw new IllegalStateException("Enter a value for " + missing.get() + ".");
        }
        ServerOperationInvocation.Builder invocation =
                ServerOperationInvocation.invocation(operation.id());
        for (ServerOperationParameter parameter : operation.parameters()) {
            String value = value(parameter.name());
            if (value.isBlank()) {
                continue;
            }
            switch (parameter.location()) {
                case PATH -> invocation.pathParameter(parameter.name(), value);
                case QUERY -> invocation.queryParameter(parameter.name(), value);
                case HEADER -> invocation.headerParameter(parameter.name(), value);
            }
        }
        if (body != null && !body.isBlank()) {
            invocation.body(body);
        }
        return invocation.build();
    }

    /**
     * A label for one parameter, suitable as a form field name.
     *
     * <p>The location is part of the label because two parameters may legitimately share a
     * name across locations, and a form showing the same word twice with no way to tell
     * them apart is a form the user cannot fill in correctly.</p>
     */
    public static String label(ServerOperationParameter parameter) {
        Objects.requireNonNull(parameter, "parameter");
        String required = parameter.isRequired() ? " *" : "";
        return switch (parameter.location()) {
            case PATH -> "Path: " + parameter.name() + required;
            case QUERY -> parameter.name() + required;
            case HEADER -> "Header: " + parameter.name() + required;
        };
    }

    /**
     * The body hint for this operation: what to paste and in what format.
     *
     * <p>Empty when the operation takes no body, so a caller can show the editor pane
     * conditionally rather than offering a box the request will refuse.</p>
     */
    public String bodyHint() {
        return switch (operation.bodyRequirement()) {
            case NONE -> "";
            case REQUIRED -> "Request body (required)"
                    + (operation.bodyContentType() == null ? "" : ", " + operation.bodyContentType());
            case OPTIONAL -> "Request body (optional)"
                    + (operation.bodyContentType() == null ? "" : ", " + operation.bodyContentType());
        };
    }

    /** True when this operation accepts a body at all. */
    public boolean acceptsBody() {
        return operation.bodyRequirement() != ServerOperation.BodyRequirement.NONE;
    }

    @Override
    public String toString() {
        return "ServerOperationForm[" + operation.id() + ", " + values.size() + " values entered]";
    }
}
