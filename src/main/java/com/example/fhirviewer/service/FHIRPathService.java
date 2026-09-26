package com.example.fhirviewer.service;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.example.fhirviewer.fhir.FhirContextFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.parser.IParser;

/**
 * Evaluates FHIRPath expressions against a live FHIR resource.
 *
 * <p>The engine is HAPI FHIR's FHIRPath implementation, created through the FHIR
 * version factory and built lazily, because starting it is expensive. Every
 * evaluation is reported as a {@link Result} that distinguishes a value, no value
 * and a broken expression, so the UI can show the difference instead of throwing.</p>
 */
public class FHIRPathService {

    /** The outcome of one evaluation. */
    public enum Status {
        /** The expression evaluated and produced at least one value. */
        VALID,
        /** The expression is syntactically or semantically wrong. */
        INVALID,
        /** The expression is correct but matched nothing in the resource. */
        EMPTY
    }

    /**
     * One evaluation outcome for one expression.
     *
     * @param expression the expression that was evaluated
     * @param status     the outcome of the evaluation
     * @param resultText the rendered values, when the status is {@link Status#VALID}
     * @param error      the reason the expression failed, when the status is
     *                   {@link Status#INVALID}
     */
    public record Result(String expression, Status status, String resultText, String error) {

        public Result {
            expression = expression == null ? "" : expression;
            resultText = resultText == null ? "" : resultText;
            error = error == null ? "" : error;
        }

        public static Result valid(String expression, String resultText) {
            return new Result(expression, Status.VALID, resultText, "");
        }

        public static Result empty(String expression) {
            return new Result(expression, Status.EMPTY, "", "");
        }

        public static Result invalid(String expression, String error) {
            return new Result(expression, Status.INVALID, "", error);
        }

        /** True when the expression produced a non empty value. */
        public boolean hasResult() {
            return status == Status.VALID && !resultText.isBlank();
        }
    }

    private final FhirContext context;
    private volatile IFhirPath engine;
    private volatile IParser jsonParser;

    /** Creates a service for the default R4 FHIR version. */
    public FHIRPathService() {
        this(FhirContextFactory.r4());
    }

    /** Creates a service for a specific FHIR context. */
    public FHIRPathService(FhirContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * Evaluates a FHIRPath expression against a resource.
     *
     * @param resource   the resource to evaluate against, or {@code null} when none
     *                   is loaded (reported as an invalid expression)
     * @param expression the FHIRPath expression, for example {@code Patient.name.family}
     * @return the outcome of the evaluation; never {@code null}
     */
    public Result evaluate(IBaseResource resource, String expression) {
        String expr = expression == null ? "" : expression.trim();
        if (expr.isEmpty()) {
            return Result.invalid(expr, "The FHIRPath expression is blank.");
        }
        if (resource == null) {
            return Result.invalid(expr, "No FHIR resource is loaded.");
        }
        try {
            IFhirPath.IParsedExpression parsed = engine().parse(expr);
            List<IBase> values = engine().evaluate(resource, parsed, IBase.class);
            if (values.isEmpty()) {
                return Result.empty(expr);
            }
            String text = render(values);
            return text.isBlank() ? Result.empty(expr) : Result.valid(expr, text);
        } catch (Exception e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            return Result.invalid(expr, detail);
        }
    }

    /** The FHIRPath engine, created on first use because it is expensive to build. */
    private IFhirPath engine() {
        IFhirPath existing = engine;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (engine == null) {
                engine = FhirContextFactory.r4FhirPath();
            }
            return engine;
        }
    }

    /** Renders the values an expression produced, separated by a comma. */
    private String render(List<IBase> values) {
        StringBuilder text = new StringBuilder();
        for (IBase value : values) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(render(value));
        }
        return text.toString();
    }

    /** Renders one value: primitives as their value, complex values as compact JSON. */
    private String render(IBase value) {
        if (value instanceof IPrimitiveType<?> primitive) {
            return primitive.hasValue() ? primitive.getValueAsString() : "";
        }
        try {
            return jsonParser().encodeToString(value);
        } catch (RuntimeException e) {
            return value.fhirType();
        }
    }

    /** The compact JSON renderer for complex results, created on first use. */
    private IParser jsonParser() {
        IParser existing = jsonParser;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (jsonParser == null) {
                jsonParser = context.newJsonParser();
            }
            return jsonParser;
        }
    }
}