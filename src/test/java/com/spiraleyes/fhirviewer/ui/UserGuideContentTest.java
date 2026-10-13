package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        // Part A's contents has 12 entries, Part B's keeps its original 16.
        assertEquals(28, targets.size(), "The contents lists have 12 + 16 entries");
        for (String target : targets) {
            assertTrue(anchors.contains(target),
                    "Contents link #" + target + " has no heading");
        }
    }
}

