package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.service.scripting.api.FhirApi;
import com.spiraleyes.fhirviewer.service.scripting.api.SmartApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ViewerApi;

/**
 * Tests the bundled user guide loads from the class path.
 */
class UserGuideContentTest {

    @Test
    @DisplayName("The bundled guide loads and is the server-functions guide")
    void loadsServerGuide() {
        String text = UserGuideContent.load();
        assertFalse(text.isBlank(), "The user guide text must not be blank");
        assertTrue(text.contains("FHIR Server Functions"),
                "Expected the server-functions guide, got:\n" + text.substring(0, Math.min(200, text.length())));
    }

    @Test
    @DisplayName("The bundled guide covers adding servers")
    void coversAddingServers() {
        String text = UserGuideContent.load();
        assertTrue(text.contains("Adding and editing servers"),
                "The guide should describe adding servers");
    }

    @Test
    @DisplayName("Every block of the bundled guide parses")
    void bundledGuideParses() {
        List<UserGuideDocument.Block> blocks =
                UserGuideDocument.parse(UserGuideContent.load());
        assertFalse(blocks.isEmpty(), "The guide must parse to blocks");
        long headings = blocks.stream().filter(block -> block instanceof UserGuideDocument.Heading).count();
        long tables = blocks.stream().filter(block -> block instanceof UserGuideDocument.Table).count();
        long rules = blocks.stream().filter(block -> block instanceof UserGuideDocument.Rule).count();
        assertTrue(headings >= 10, "Expected the section headings, found " + headings);
        assertTrue(tables >= 3, "Expected the Button/Field/Kind grids, found " + tables);
        assertTrue(rules >= 3, "Expected the section dividers, found " + rules);
    }

    @Test
    @DisplayName("Raw markdown markers do not survive parsing")
    void noRawMarkers() {
        List<UserGuideDocument.Block> blocks =
                UserGuideDocument.parse(UserGuideContent.load());
        List<List<UserGuideDocument.Span>> spanGroups = new java.util.ArrayList<>();
        for (UserGuideDocument.Block block : blocks) {
            if (block instanceof UserGuideDocument.Table table) {
                spanGroups.addAll(table.header());
                for (List<List<UserGuideDocument.Span>> row : table.rows()) {
                    spanGroups.addAll(row);
                }
            } else if (block instanceof UserGuideDocument.Heading heading) {
                spanGroups.add(heading.spans());
            } else if (block instanceof UserGuideDocument.Paragraph paragraph) {
                spanGroups.add(paragraph.spans());
            } else if (block instanceof UserGuideDocument.BulletList list) {
                for (UserGuideDocument.ListItem item : list.items()) {
                    spanGroups.add(item.spans());
                    for (UserGuideDocument.ListItem child : item.children()) {
                        spanGroups.add(child.spans());
                    }
                }
            } else if (block instanceof UserGuideDocument.OrderedList ordered) {
                for (UserGuideDocument.ListItem item : ordered.items()) {
                    spanGroups.add(item.spans());
                    for (UserGuideDocument.ListItem child : item.children()) {
                        spanGroups.add(child.spans());
                    }
                }
            } else if (block instanceof UserGuideDocument.Quote quote) {
                spanGroups.add(quote.spans());
            }
        }
        for (List<UserGuideDocument.Span> spans : spanGroups) {
            assertNoMarkers(spans);
        }
    }

    private static void assertNoMarkers(List<UserGuideDocument.Span> spans) {
        for (UserGuideDocument.Span span : spans) {
            if (span instanceof UserGuideDocument.Text typed) {
                assertFalse(typed.text().contains("**"),
                        "Bold markers must become spans, not text: [" + typed.text() + "]");
                assertFalse(typed.text().contains("](#"),
                        "Link targets must become links, not text: [" + typed.text() + "]");
            } else if (span instanceof UserGuideDocument.Strong typed) {
                assertNoMarkers(typed.inner());
            } else if (span instanceof UserGuideDocument.Emphasis typed) {
                assertNoMarkers(typed.inner());
            } else if (span instanceof UserGuideDocument.Link typed) {
                assertNoMarkers(typed.inner());
            }
        }
    }

    @Test
    @DisplayName("Fenced code samples stay code blocks with their line breaks")
    void fencedCodeSamplesStayCodeBlocks() {
        for (String guide : List.of(UserGuideContent.loadSmartGuide(),
                UserGuideContent.loadPhase6Guide())) {
            List<UserGuideDocument.Block> blocks = UserGuideDocument.parse(guide);
            List<String> codeBlocks = blocks.stream()
                    .filter(block -> block instanceof UserGuideDocument.CodeBlock)
                    .map(block -> ((UserGuideDocument.CodeBlock) block).text())
                    .toList();
            assertFalse(codeBlocks.isEmpty(), "Both guides should contain fenced code samples");
            assertTrue(codeBlocks.stream().anyMatch(code -> code.contains("smart.isSessionActive()")),
                    "The SMART sample should be a code block");
            assertTrue(codeBlocks.stream().anyMatch(code -> code.contains("\n    log.warn")),
                    "Code indentation and line breaks must survive parsing");
            for (UserGuideDocument.Block block : blocks) {
                if (block instanceof UserGuideDocument.CodeBlock code) {
                    assertFalse(code.text().contains("```"), "The fences themselves must not render");
                } else if (block instanceof UserGuideDocument.Paragraph paragraph) {
                    assertFalse(UserGuideDocument.plainText(paragraph.spans()).contains("```"),
                            "A fenced sample must not collapse into a paragraph");
                }
            }
        }
    }

    @Test
    @DisplayName("The documented SMART and FHIR calls still exist")
    void documentedApiCallsExist() {
        assertDoesNotThrow(() -> {
            SmartApi.class.getMethod("isSessionActive");
            SmartApi.class.getMethod("grantedScopes");
            SmartApi.class.getMethod("launchPatientId");
            SmartApi.class.getMethod("launchEncounterId");
            SmartApi.class.getMethod("read", String.class, String.class);
            FhirApi.class.getMethod("read", String.class, String.class);
            FhirApi.class.getMethod("search", String.class,
                    com.spiraleyes.fhirviewer.server.SearchCriterion[].class);
            ViewerApi.class.getMethod("showResource",
                    org.hl7.fhir.instance.model.api.IBaseResource.class);
        });

        String smart = UserGuideContent.loadSmartGuide();
        assertTrue(smart.contains("smart.isSessionActive()"));
        assertTrue(smart.contains("smart.read(\"Patient\""));
        assertTrue(UserGuideContent.loadPhase6Guide().contains("fhir.read(\"Patient\""));
    }

    @Test
    @DisplayName("Every internal link in every bundled guide resolves")
    void allGuideAnchorsResolve() {
        Pattern internal = Pattern.compile("\\]\\(#([^)]+)\\)");
        for (String guide : List.of(UserGuideContent.load(), UserGuideContent.loadSmartGuide(),
                UserGuideContent.loadPhase6Guide())) {
            List<String> anchors = UserGuideDocument.parse(guide).stream()
                    .filter(block -> block instanceof UserGuideDocument.Heading)
                    .map(block -> UserGuideDocument.anchorOf((UserGuideDocument.Heading) block))
                    .toList();
            Matcher links = internal.matcher(guide);
            while (links.find()) {
                assertTrue(anchors.contains(links.group(1)),
                        "Internal link #" + links.group(1) + " has no heading: " + links.group());
            }
        }
    }

    @Test
    @DisplayName("The docs sources and bundled help copies stay identical")
    void docsAndBundledHelpStayInSync() throws IOException {
        assertParity("docs/user-guide/User-Guide.md", UserGuideContent.load());
        assertParity("docs/smartonfhir/USER-GUIDE-SMART.md", UserGuideContent.loadSmartGuide());
        assertParity("docs/scripting/Scripting-Guide.md", UserGuideContent.loadPhase6Guide());
    }

    private static void assertParity(String path, String bundled) throws IOException {
        String source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        assertEquals(idempotent(source), idempotent(bundled),
                path + " and its bundled help copy have drifted");
    }

    private static String idempotent(String text) {
        return text.replace("\r\n", "\n");
    }

    @Test
    @DisplayName("The contents links resolve to headings in the guide")
    void contentsLinksResolve() {
        List<UserGuideDocument.Block> blocks =
                UserGuideDocument.parse(UserGuideContent.load());
        List<String> anchors = blocks.stream()
                .filter(block -> block instanceof UserGuideDocument.Heading)
                .map(block -> UserGuideDocument.anchorOf((UserGuideDocument.Heading) block))
                .toList();
        List<String> targets = blocks.stream()
                .filter(block -> block instanceof UserGuideDocument.OrderedList)
                .flatMap(block -> ((UserGuideDocument.OrderedList) block).items().stream())
                .flatMap(item -> item.spans().stream())
                .filter(span -> span instanceof UserGuideDocument.Link)
                .map(span -> ((UserGuideDocument.Link) span).target().replaceFirst("^#", ""))
                .toList();
        // Part A's contents has 13 entries, Part B's keeps its original 16.
        assertEquals(29, targets.size(), "The contents lists have 13 + 16 entries");
        for (String target : targets) {
            assertTrue(anchors.contains(target),
                    "Contents link #" + target + " has no heading");
        }
    }
}

