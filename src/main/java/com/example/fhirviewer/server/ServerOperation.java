package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.example.fhirviewer.server.rest.RestMethod;

/**
 * One operation a server plugin offers, described as data.
 *
 * <p>This is the fine-grained counterpart to {@link ServerVendorAction}. A vendor action
 * models a whole screen and is one string wide; this models a callable endpoint — a verb,
 * a path, a parameter list and a declared answer — so a UI can discover it, collect input
 * and run it without knowing what the endpoint <em>means</em>. It is built alongside
 * {@code ServerVendorAction} rather than replacing it, so a plugin compiled against the
 * older contract keeps working through the existing {@code default} methods.</p>
 *
 * <p><b>Data, not behaviour.</b> An operation says where to send a request and what to
 * expect back; it never performs one. That split is what keeps vendor code in the plugin:
 * the core reads this descriptor, builds a request from it through the shared transport,
 * and never learns that {@code /cdr/api/refresh-tokens} is a Smile endpoint.</p>
 *
 * <p><b>The path is a template.</b> {@code "admin/export/{jobId}"} names one path segment
 * that comes from the caller. Placeholders and declared
 * {@link ServerOperationParameter.Location#PATH} parameters are cross-checked here, so a
 * template naming a parameter the operation never declared — or a declared parameter no
 * template names — is refused while the plugin is still being written, rather than
 * producing a request with a literal <code>{jobId}</code> in it.</p>
 *
 * <p>Instances are immutable and built with {@link #builder(String, RestMethod, String)}.</p>
 */
public final class ServerOperation {

    private final String id;
    private final String displayName;
    private final String description;
    private final Category category;
    private final RestMethod method;
    private final String pathTemplate;
    private final List<ServerOperationParameter> parameters;
    private final BodyRequirement bodyRequirement;
    private final String bodyContentType;
    private final ResultKind expectedResult;
    private final boolean requiresAuthentication;
    private final String requiredCapability;

    private ServerOperation(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.category = builder.category;
        this.method = builder.method;
        this.pathTemplate = builder.pathTemplate;
        this.parameters = List.copyOf(builder.parameters);
        this.bodyRequirement = builder.bodyRequirement;
        this.bodyContentType = builder.bodyContentType;
        this.expectedResult = builder.expectedResult;
        this.requiresAuthentication = builder.requiresAuthentication;
        this.requiredCapability = builder.requiredCapability;
        validateParametersAgainstTemplate();
    }

    /** Where an operation appears in a UI's grouping, and how privileged it is. */
    public enum Category {
        /** A standard FHIR interaction, declared so a UI can list it beside the others. */
        STANDARD,
        /** A vendor-specific extension to FHIR REST, such as an export or a validate call. */
        VENDOR,
        /** Server administration. Usually privileged, and shown apart from the rest. */
        ADMINISTRATION
    }

    /** Whether, and in what form, the caller must supply a request body. */
    public enum BodyRequirement {
        /** The operation takes no body; supplying one is refused. */
        NONE,
        /** A body may be supplied. */
        OPTIONAL,
        /** A body must be supplied, and the request is refused without one. */
        REQUIRED
    }

    /**
     * What a body is, both as a declaration of what to ask for and as an observation of
     * what the server sent.
     *
     * <p>As a declaration, {@link #ANY} is the honest default for a vendor endpoint nobody
     * has characterised. As an observation, a {@link ServerOperationResult} never reports
     * {@code ANY}: an answer is classified from its content type and, where the content
     * type allows it, from what the body actually parses as.</p>
     */
    public enum ResultKind {
        /** No answer body is expected, for example a delete reporting only a status. */
        NONE,
        /** Any single FHIR resource, the type not fixed in advance. */
        FHIR_RESOURCE,
        /** A FHIR {@code Bundle}, the shape a bulk or export operation returns. */
        BUNDLE,
        /** A FHIR {@code OperationOutcome}, which is also how a refusal is reported. */
        OPERATION_OUTCOME,
        /** JSON that is not a FHIR resource. */
        JSON,
        /** XML that is not a FHIR resource. */
        XML,
        /** Plain text, including a CSV or log extract. */
        TEXT,
        /** The caller does not know, and the body is returned unparsed. */
        ANY
    }

    /**
     * The stable id a caller passes back to run this operation, for example
     * {@code export}.
     *
     * <p>Held to a URL-path-segment rule even though it is only used for a lookup: an id
     * carrying {@code /} or {@code ?} would otherwise be able to name a different endpoint
     * wherever it is logged or echoed into a dialog.</p>
     */
    public String id() {
        return id;
    }

    /** The name shown to the user. */
    public String displayName() {
        return displayName;
    }

    /** One or two sentences on what the operation does. Never {@code null}. */
    public String description() {
        return description;
    }

    /** How the operation should be grouped and presented. */
    public Category category() {
        return category;
    }

    /** The HTTP verb the operation is invoked with. */
    public RestMethod method() {
        return method;
    }

    /** The path below the base URL, with {@code {name}} placeholders for path parameters. */
    public String pathTemplate() {
        return pathTemplate;
    }

    /** The declared parameters, in declaration order. Never {@code null}. */
    public List<ServerOperationParameter> parameters() {
        return parameters;
    }

    /** Whether a request body is required, optional or refused. */
    public BodyRequirement bodyRequirement() {
        return bodyRequirement;
    }

    /** The media type a supplied body is sent as, or {@code null} for the FHIR default. */
    public String bodyContentType() {
        return bodyContentType;
    }

    /** What this operation says it returns. {@link ResultKind#ANY} when uncharacterised. */
    public ResultKind expectedResult() {
        return expectedResult;
    }

    /**
     * True when the operation refuses to run without credentials.
     *
     * <p>Checked against the session before a request is built, so an anonymous caller is
     * told the operation needs authentication rather than being sent to the server to be
     * refused with a {@code 401} it could have been warned about locally.</p>
     */
    public boolean requiresAuthentication() {
        return requiresAuthentication;
    }

    /**
     * A token naming what the server must advertise for this operation to be offered, or
     * {@code null} when nothing beyond a reachable server is required.
     *
     * <p>Deliberately an opaque string rather than a capability expression: what a vendor
     * needs is the vendor's own vocabulary, and putting that vocabulary in core would be
     * the branching this whole seam exists to avoid. A plugin narrows the list it returns
     * when the connected server does not match.</p>
     */
    public String requiredCapability() {
        return requiredCapability;
    }

    /** The declared parameter with this name, or empty when the operation has none. */
    public Optional<ServerOperationParameter> parameter(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (ServerOperationParameter parameter : parameters) {
            if (parameter.name().equals(name)) {
                return Optional.of(parameter);
            }
        }
        return Optional.empty();
    }

    /** The declared parameters that travel in one part of the request. */
    public List<ServerOperationParameter> parametersAt(ServerOperationParameter.Location location) {
        List<ServerOperationParameter> matching = new ArrayList<>();
        for (ServerOperationParameter parameter : parameters) {
            if (parameter.location() == location) {
                matching.add(parameter);
            }
        }
        return List.copyOf(matching);
    }

    /** The path parameters the template names, which is what an invocation must supply. */
    public List<ServerOperationParameter> pathParameters() {
        return parametersAt(ServerOperationParameter.Location.PATH);
    }

    /** True when this operation is happy with the body the caller supplied. */
    public boolean isSatisfiedBy(String suppliedBody) {
        return switch (bodyRequirement) {
            case NONE -> suppliedBody == null || suppliedBody.isBlank();
            case OPTIONAL -> true;
            case REQUIRED -> suppliedBody != null && !suppliedBody.isBlank();
        };
    }

    /** What to tell the user when the supplied body does not satisfy the requirement. */
    public String bodyRequirementMessage() {
        return switch (bodyRequirement) {
            case NONE -> "The '" + displayName + "' operation does not take a request body.";
            case OPTIONAL -> null;
            case REQUIRED -> "The '" + displayName + "' operation needs a request body.";
        };
    }

    /**
     * Refuses a template and a parameter list that disagree.
     *
     * <p>Both directions matter. A placeholder no parameter declares would be sent
     * literally, so the server would answer about a resource named <code>{jobId}</code>; a
     * declared path parameter no template names would silently drop a value the caller
     * believed was being sent.</p>
     */
    private void validateParametersAgainstTemplate() {
        List<String> placeholders = OperationPathTemplate.placeholdersIn(pathTemplate);
        for (String placeholder : placeholders) {
            boolean declared = pathParameters().stream()
                    .anyMatch(parameter -> parameter.name().equals(placeholder));
            if (!declared) {
                throw new IllegalArgumentException("The path of operation '" + id + "' names {"
                        + placeholder + "} but declares no such path parameter.");
            }
        }
        for (ServerOperationParameter parameter : pathParameters()) {
            if (!placeholders.contains(parameter.name())) {
                throw new IllegalArgumentException("Operation '" + id + "' declares the path parameter '"
                        + parameter.name() + "' but its path does not name it.");
            }
        }
    }

    /** Never prints parameter values or a body: both routinely carry patient data. */
    @Override
    public String toString() {
        return method + " " + pathTemplate + " [" + category + "] as " + id;
    }

    /** Starts a descriptor for an operation with a given id, verb and path template. */
    public static Builder builder(String id, RestMethod method, String pathTemplate) {
        return new Builder(id, method, pathTemplate);
    }

    /**
     * Assembles a {@link ServerOperation}.
     *
     * <p>Validation happens in {@link #build()} rather than in each setter, so a partially
     * configured builder is a normal intermediate state and a plugin author sees every
     * problem with their declaration at once instead of one setter at a time.</p>
     */
    public static final class Builder {

        private final String id;
        private final RestMethod method;
        private final String pathTemplate;
        private final List<ServerOperationParameter> parameters = new ArrayList<>();
        private String displayName;
        private String description = "";
        private Category category = Category.VENDOR;
        private BodyRequirement bodyRequirement = BodyRequirement.NONE;
        private String bodyContentType;
        private ResultKind expectedResult = ResultKind.ANY;
        private boolean requiresAuthentication;
        private String requiredCapability;

        private Builder(String id, RestMethod method, String pathTemplate) {
            this.id = requireToken(id, "An operation id is required.");
            this.method = Objects.requireNonNull(method, "method");
            this.pathTemplate = requireToken(pathTemplate, "An operation path is required.");
            this.displayName = this.id;
        }

        /**
         * The name shown to the user. Defaults to the id, so the shortest possible
         * declaration — an id, a verb and a path — is already a usable one.
         */
        public Builder displayName(String displayName) {
            this.displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
            return this;
        }

        /** One or two sentences on what the operation does. */
        public Builder description(String description) {
            this.description = description == null ? "" : description.trim();
            return this;
        }

        /** How the operation should be grouped. Defaults to {@link Category#VENDOR}. */
        public Builder category(Category category) {
            this.category = Objects.requireNonNull(category, "category");
            return this;
        }

        /** Declares that the caller must supply a body. */
        public Builder requiresBody(String contentType) {
            this.bodyRequirement = BodyRequirement.REQUIRED;
            this.bodyContentType = contentType;
            return this;
        }

        /** Declares that the caller may supply a body. */
        public Builder acceptsBody(String contentType) {
            this.bodyRequirement = BodyRequirement.OPTIONAL;
            this.bodyContentType = contentType;
            return this;
        }

        /** Declares what the server is expected to send back. */
        public Builder returns(ResultKind expectedResult) {
            this.expectedResult = Objects.requireNonNull(expectedResult, "expectedResult");
            return this;
        }

        /** Declares that the operation will not run without credentials. */
        public Builder requiresAuthentication() {
            this.requiresAuthentication = true;
            return this;
        }

        /** Declares the capability token the server must advertise. */
        public Builder requiresCapability(String requiredCapability) {
            this.requiredCapability = requiredCapability == null || requiredCapability.isBlank()
                    ? null : requiredCapability.trim();
            return this;
        }

        /**
         * Declares a parameter, in the order the UI should show them.
         *
         * <p>Order is preserved rather than sorted, because the order a plugin writes is
         * the order that makes sense to a user filling the form.</p>
         */
        public Builder parameter(ServerOperationParameter parameter) {
            parameters.add(Objects.requireNonNull(parameter, "parameter"));
            return this;
        }

        /** Declares a path parameter, which the path template must also name. */
        public Builder pathParameter(String name, String description) {
            return parameter(ServerOperationParameter.path(name, description, true));
        }

        /** Declares an optional path parameter, which the path template must also name. */
        public Builder optionalPathParameter(String name, String description) {
            return parameter(ServerOperationParameter.path(name, description, false));
        }

        /** Declares a query parameter. */
        public Builder queryParameter(String name, String description, boolean required) {
            return parameter(ServerOperationParameter.query(name, description, required));
        }

        /**
         * Declares a header parameter.
         *
         * <p>The value is not a secret by default, so it appears in the descriptor's
         * {@code toString()} and can be logged. A header that <em>does</em> carry a
         * credential is refused at execution time by {@link PluginOperationClient} unless it
         * is declared secret here, which keeps {@code Authorization} on the session's
         * {@link ServerAuthentication} and off a parameter list.</p>
         */
        public Builder headerParameter(String name, String description, boolean required) {
            return parameter(ServerOperationParameter.header(name, description, required));
        }

        /**
         * Builds the descriptor, or explains why it cannot be built.
         *
         * @throws IllegalArgumentException when a body is required by a verb that cannot
         *         carry one, when a parameter name is declared twice, or when the path
         *         template and the path parameters disagree
         */
        public ServerOperation build() {
            if (bodyRequirement != BodyRequirement.NONE && !method.allowsRequestBody()) {
                throw new IllegalArgumentException("Operation '" + id + "' is a " + method
                        + ", which cannot carry the request body it declares.");
            }
            rejectDuplicateParameterNames();
            rejectUnsafeHeaderDeclarations();
            return new ServerOperation(this);
        }

        /** Two parameters with one name would make the request that gets built ambiguous. */
        private void rejectDuplicateParameterNames() {
            for (int i = 0; i < parameters.size(); i++) {
                for (int j = i + 1; j < parameters.size(); j++) {
                    if (parameters.get(i).name().equals(parameters.get(j).name())) {
                        throw new IllegalArgumentException("Operation '" + id + "' declares the parameter '"
                                + parameters.get(i).name() + "' twice.");
                    }
                }
            }
        }

        /**
         * Refuses a header parameter naming a field the transport owns.
         *
         * <p>{@code Content-Length}, {@code Transfer-Encoding} and {@code Host} are computed
         * by the HTTP client from the body it is actually sending; a declared value would be
         * either ignored or would describe a different request. Catching it in
         * {@code build()} means the plugin author hears about it while writing the
         * declaration, not from a server that returns a puzzling 400.</p>
         */
        private void rejectUnsafeHeaderDeclarations() {
            for (ServerOperationParameter parameter : parameters) {
                if (parameter.location() == ServerOperationParameter.Location.HEADER
                        && PluginOperationClient.isTransportOwnedHeader(parameter.name())) {
                    throw new IllegalArgumentException("Operation '" + id + "' declares the header '"
                            + parameter.name() + "', which the HTTP transport computes itself.");
                }
            }
        }
    }

    /**
     * A single URL path segment, free of anything that would change the path.
     *
     * <p>Shared by the id and the path template. An operation's template is a literal path
     * with named holes in it, and a template carrying a query string or a fragment would
     * smuggle one past the query-parameter model that already exists.</p>
     */
    private static String requireToken(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.contains("?") || trimmed.contains("#") || trimmed.contains(" ")) {
            throw new IllegalArgumentException("'" + value
                    + "' cannot contain '?', '#' or a space; it is a path, not a URL with a query.");
        }
        return trimmed;
    }
}
