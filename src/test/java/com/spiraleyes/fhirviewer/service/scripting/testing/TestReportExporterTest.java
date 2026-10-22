package com.spiraleyes.fhirviewer.service.scripting.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestReportExporterTest {

    private TestSuiteResult makeResult() {
        TestCaseResult pass = new TestCaseResult("pass-test",
                TestOutcome.PASSED, Duration.ofMillis(10), "OK", null, false, "dev",
                Instant.parse("2024-01-01T00:00:00Z"));
        TestCaseResult fail = new TestCaseResult("fail-test",
                TestOutcome.FAILED, Duration.ofMillis(20), "assertion failed", null, false, "dev",
                Instant.parse("2024-01-01T00:00:01Z"));
        TestSuiteResult result = new TestSuiteResult("my-suite",
                List.of(pass, fail), Duration.ofMillis(30),
                Instant.parse("2024-01-01T00:00:00Z"), false);
        return result;
    }

    @Test
    void toJsonContainsExpectedFields(@TempDir Path tempDir) throws Exception {
        TestReportExporter exporter = new TestReportExporter();
        String json = exporter.toJson(makeResult());
        assertTrue(json.contains("\"suite\":\"my-suite\""));
        assertTrue(json.contains("\"total\":2"));
        assertTrue(json.contains("\"passed\":1"));
        assertTrue(json.contains("\"failed\":1"));
        assertTrue(json.contains("\"errored\":0"));
        assertTrue(json.contains("\"skipped\":0"));
        assertTrue(json.contains("\"cancelled\":false"));
        assertTrue(json.contains("\"name\":\"pass-test\""));
        assertTrue(json.contains("\"name\":\"fail-test\""));
        assertTrue(json.contains("\"outcome\":\"PASSED\""));
        assertTrue(json.contains("\"outcome\":\"FAILED\""));
    }

    @Test
    void exportJsonWritesFile(@TempDir Path tempDir) throws Exception {
        TestReportExporter exporter = new TestReportExporter();
        Path target = tempDir.resolve("report.json");
        exporter.exportJson(makeResult(), target);
        String content = Files.readString(target);
        assertTrue(content.contains("\"suite\":\"my-suite\""));
    }

    @Test
    void toHtmlContainsExpectedFields() {
        TestReportExporter exporter = new TestReportExporter();
        String html = exporter.toHtml(makeResult());
        assertTrue(html.contains("my-suite"));
        assertTrue(html.contains("pass-test"));
        assertTrue(html.contains("fail-test"));
        assertTrue(html.contains("PASSED"));
        assertTrue(html.contains("FAILED"));
        assertTrue(html.contains("10"));
        assertTrue(html.contains("20"));
    }

    @Test
    void exportHtmlWritesFile(@TempDir Path tempDir) throws Exception {
        TestReportExporter exporter = new TestReportExporter();
        Path target = tempDir.resolve("report.html");
        exporter.exportHtml(makeResult(), target);
        String content = Files.readString(target);
        assertTrue(content.contains("my-suite"));
    }
}
