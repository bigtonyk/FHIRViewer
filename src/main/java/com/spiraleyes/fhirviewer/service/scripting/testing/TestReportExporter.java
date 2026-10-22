package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Exports a {@link TestSuiteResult} to JSON or HTML. Output is written with
 * UTF-8 and contains no tokens or credentials by construction (only test names,
 * outcomes, durations and assertion messages).
 */
public final class TestReportExporter {

    private static final ObjectMapper JSON = new ObjectMapper();

    public void exportJson(TestSuiteResult result, Path target) throws IOException {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(target, "target");
        Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
        Files.writeString(target, toJson(result), StandardCharsets.UTF_8);
    }

    public void exportHtml(TestSuiteResult result, Path target) throws IOException {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(target, "target");
        Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
        Files.writeString(target, toHtml(result), StandardCharsets.UTF_8);
    }

    public String toJson(TestSuiteResult result) {
        ObjectNode root = JSON.createObjectNode();
        root.put("suite", result.suiteName());
        root.put("startedAt", result.startedAt().toString());
        root.put("durationMs", result.duration().toMillis());
        root.put("total", result.total());
        root.put("passed", result.passed());
        root.put("failed", result.failed());
        root.put("errored", result.errored());
        root.put("skipped", result.skipped());
        root.put("cancelled", result.cancelled());
        ArrayNode arr = root.putArray("tests");
        for (TestCaseResult r : result.results()) {
            ObjectNode t = arr.addObject();
            t.put("name", r.name());
            t.put("outcome", r.outcome().name());
            t.put("durationMs", r.duration().toMillis());
            t.put("message", r.message());
            t.put("destructive", r.destructive());
            t.put("server", r.serverLabel());
            t.put("startedAt", r.startedAt().toString());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize report", e);
        }
    }

    public String toHtml(TestSuiteResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
                .append(escape(result.suiteName())).append(" - Test Report</title>");
        sb.append("<style>body{font-family:sans-serif;margin:2rem;}table{border-collapse:collapse;width:100%;}")
                .append("th,td{border:1px solid #ccc;padding:.5rem;text-align:left;} .PASSED{color:green;}")
                .append(".FAILED{color:#b00;} .ERROR{color:#900;} .SKIPPED{color:#666;} .CANCELLED{color:#960;}</style></head><body>");
        sb.append("<h1>").append(escape(result.suiteName())).append("</h1>");
        sb.append("<p>Total: ").append(result.total())
                .append(" | Passed: ").append(result.passed())
                .append(" | Failed: ").append(result.failed())
                .append(" | Errors: ").append(result.errored())
                .append(" | Skipped: ").append(result.skipped())
                .append(" | Duration: ").append(result.duration().toMillis()).append(" ms</p>");
        sb.append("<table><thead><tr><th>Name</th><th>Outcome</th><th>Duration (ms)</th>")
                .append("<th>Server</th><th>Message</th></tr></thead><tbody>");
        for (TestCaseResult r : result.results()) {
            sb.append("<tr><td>").append(escape(r.name())).append("</td>")
                    .append("<td class=\"").append(r.outcome().name()).append("\">")
                    .append(r.outcome().name()).append("</td>")
                    .append("<td>").append(r.duration().toMillis()).append("</td>")
                    .append("<td>").append(escape(r.serverLabel())).append("</td>")
                    .append("<td>").append(escape(r.message())).append("</td></tr>");
        }
        sb.append("</tbody></table></body></html>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
