package com.spiraleyes.fhirviewer.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A markdown document as plain data: blocks of headings, paragraphs,
 * lists, tables, quotes and rules, with inline bold, italic, code
 * and links.
 *
 * <p>Only the markdown the bundled user guide uses is covered: ATX
 * headings, unordered and ordered lists (including wrapped continuation
 * lines), GitHub style tables, blockquotes, horizontal rules and inline
 * {@code **bold**}, {@code *italic*}, backtick code and
 * {@code [text](target)} links. Fenced code blocks and images do not
 * appear in the guide and are not handled.</p>
 *
 * <p>Free of JavaFX types so the parsing is tested without starting
 * the toolkit; {@link UserGuideView} turns the blocks into nodes.</p>
 */
public final class UserGuideDocument {

    /** One piece of inline content inside a paragraph, heading or cell. */
    public sealed interface Span permits Text, Emphasis, Strong, Code, Link {
    }

    /** Plain text. */
    public record Text(String text) implements Span {
    }

    /** Italic text. */
    public record Emphasis(List<Span> inner) implements Span {
    }

    /** Bold text. */
    public record Strong(List<Span> inner) implements Span {
    }

    /** Inline code. */
    public record Code(String text) implements Span {
    }

    /** A link: display spans plus the target. */
    public record Link(List<Span> inner, String target) implements Span {
    }

    /** One block of the document. */
    public sealed interface Block permits Paragraph, Heading, BulletList, OrderedList, Table, Quote, Rule {
    }

    /** A paragraph of inline spans. */
    public record Paragraph(List<Span> spans) implements Block {
    }

    /** An ATX heading: level 1 to 4, with inline spans. */
    public record Heading(int level, List<Span> spans) implements Block {
    }

    /** One item of a bullet or numbered list, with its own lines. */
    public record ListItem(List<Span> spans, List<ListItem> children) {
    }

    /** A bullet list. */
    public record BulletList(List<ListItem> items) implements Block {
    }

    /** A numbered list, with the starting number of the first item. */
    public record OrderedList(int start, List<ListItem> items) implements Block {
    }

    /** A GitHub style table: header row plus body rows, all inline spans. */
    public record Table(List<List<Span>> header, List<List<List<Span>>> rows) implements Block {
    }

    /** A blockquote: the quoted lines as inline spans. */
    public record Quote(List<Span> spans) implements Block {
    }

    /** A horizontal rule. */
    public record Rule() implements Block {
    }

    /** Parses the markdown into blocks. */
    public static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<String> paragraph = new ArrayList<>();
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, blocks);
                index++;
                continue;
            }
            if (trimmed.equals("---")) {
                flushParagraph(paragraph, blocks);
                blocks.add(new Rule());
                index++;
                continue;
            }
            if (trimmed.startsWith("#")) {
                flushParagraph(paragraph, blocks);
                blocks.add(parseHeading(trimmed));
                index++;
                continue;
            }
            if (trimmed.startsWith(">")) {
                flushParagraph(paragraph, blocks);
                StringBuilder quoted = new StringBuilder();
                while (index < lines.length && lines[index].strip().startsWith(">")) {
                    String content = lines[index].strip().substring(1);
                    if (content.startsWith(" ")) {
                        content = content.substring(1);
                    }
                    if (quoted.length() > 0) {
                        quoted.append(' ');
                    }
                    quoted.append(content);
                    index++;
                }
                blocks.add(new Quote(parseInline(quoted.toString())));
                continue;
            }
            if (trimmed.startsWith("|") && index + 1 < lines.length
                    && isDelimiterRow(lines[index + 1].strip())) {
                flushParagraph(paragraph, blocks);
                index = parseTable(lines, index, blocks);
                continue;
            }
            if (isBullet(trimmed) || isOrdered(trimmed)) {
                flushParagraph(paragraph, blocks);
                index = parseList(lines, index, blocks);
                continue;
            }
            paragraph.add(trimmed);
            index++;
        }
        flushParagraph(paragraph, blocks);
        return List.copyOf(blocks);
    }

    private static void flushParagraph(List<String> lines, List<Block> blocks) {
        if (lines.isEmpty()) {
            return;
        }
        blocks.add(new Paragraph(parseInline(String.join(" ", lines))));
        lines.clear();
    }

    private static Heading parseHeading(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        String text = trimmed.substring(Math.min(level + 1, trimmed.length()));
        return new Heading(Math.min(Math.max(level, 1), 4), parseInline(text.strip()));
    }

    private static boolean isBullet(String trimmed) {
        return trimmed.startsWith("- ");
    }

    private static boolean isOrdered(String trimmed) {
        int dot = trimmed.indexOf(". ");
        if (dot < 1) {
            return false;
        }
        for (int i = 0; i < dot; i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int orderedNumber(String trimmed) {
        return Integer.parseInt(trimmed.substring(0, trimmed.indexOf(". ")));
    }

    private static boolean isDelimiterRow(String trimmed) {
        if (!trimmed.startsWith("|")) {
            return false;
        }
        String body = trimmed.substring(1);
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] cells = body.split("\\|", -1);
        if (cells.length == 0) {
            return false;
        }
        for (String cell : cells) {
            String part = cell.strip().replace(":", "");
            if (part.isEmpty() || !part.chars().allMatch(c -> c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitRow(String trimmed) {
        String body = trimmed;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] cells = body.split("\\|", -1);
        List<String> result = new ArrayList<>(cells.length);
        for (String cell : cells) {
            result.add(cell.strip());
        }
        return result;
    }

    private static int parseTable(String[] lines, int index, List<Block> blocks) {
        List<List<Span>> header = new ArrayList<>();
        for (String cell : splitRow(lines[index].strip())) {
            header.add(parseInline(cell));
        }
        List<List<List<Span>>> rows = new ArrayList<>();
        int cursor = index + 2;
        while (cursor < lines.length && lines[cursor].strip().startsWith("|")) {
            List<List<Span>> row = new ArrayList<>();
            for (String cell : splitRow(lines[cursor].strip())) {
                row.add(parseInline(cell));
            }
            rows.add(List.copyOf(row));
            cursor++;
        }
        blocks.add(new Table(List.copyOf(header), List.copyOf(rows)));
        return cursor;
    }

    private static int parseList(String[] lines, int index, List<Block> blocks) {
        boolean ordered = isOrdered(lines[index].strip());
        List<ListItem> items = new ArrayList<>();
        int start = ordered ? orderedNumber(lines[index].strip()) : 1;
        int cursor = index;
        while (cursor < lines.length) {
            String trimmed = lines[cursor].strip();
            boolean bullet = isBullet(trimmed);
            boolean numbered = isOrdered(trimmed);
            if ((!ordered && !bullet) || (ordered && !numbered)) {
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(">")
                        || trimmed.equals("---") || trimmed.startsWith("|")) {
                    break;
                }
                if (items.isEmpty()) {
                    break;
                }
                // A wrapped continuation line of the current item.
                ListItem current = items.remove(items.size() - 1);
                items.add(new ListItem(
                        parseInline(plainText(current.spans()) + " " + trimmed), current.children()));
                cursor++;
                continue;
            }
            String text = bullet ? trimmed.substring(2) : trimmed.substring(trimmed.indexOf(". ") + 2);
            List<ListItem> children = new ArrayList<>();
            cursor++;
            // Nested bullet items belong to this item.
            while (cursor < lines.length) {
                String raw = lines[cursor];
                String nested = raw.strip();
                if (!isBullet(nested) || raw.startsWith(" ") || raw.startsWith("\t")) {
                    if (isBullet(nested) && leadingSpaces(raw) >= 3) {
                        children.add(new ListItem(
                                parseInline(nested.substring(2).strip()), List.of()));
                        cursor++;
                        continue;
                    }
                    break;
                }
                break;
            }
            items.add(new ListItem(parseInline(text.strip()), List.copyOf(children)));
        }
        if (ordered) {
            blocks.add(new OrderedList(start, List.copyOf(items)));
        } else {
            blocks.add(new BulletList(List.copyOf(items)));
        }
        return cursor;
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    /** Parses inline spans: links, bold, italic and backtick code. */
    static List<Span> parseInline(String text) {
        // The guide marks one removed table row with strikethrough; the row is
        // kept for history, so unwrap the markers rather than dropping it.
        String input = text.replace("~~", "");
        List<Span> spans = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (current == '`') {
                int end = input.indexOf('`', index + 1);
                if (end < 0) {
                    plain.append(current);
                    index++;
                    continue;
                }
                flushText(plain, spans);
                spans.add(new Code(input.substring(index + 1, end)));
                index = end + 1;
                continue;
            }
            if (current == '[') {
                int endText = input.indexOf(']', index + 1);
                int openTarget = endText >= 0 && endText + 1 < input.length()
                        && input.charAt(endText + 1) == '(' ? endText + 1 : -1;
                int endTarget = openTarget >= 0 ? input.indexOf(')', openTarget + 1) : -1;
                if (endText < 0 || openTarget < 0 || endTarget < 0) {
                    plain.append(current);
                    index++;
                    continue;
                }
                flushText(plain, spans);
                spans.add(new Link(
                        parseInline(input.substring(index + 1, endText)),
                        input.substring(openTarget + 1, endTarget)));
                index = endTarget + 1;
                continue;
            }
            if (current == '*' && index + 1 < input.length() && input.charAt(index + 1) == '*') {
                int end = input.indexOf("**", index + 2);
                if (end < 0) {
                    plain.append(current);
                    index++;
                    continue;
                }
                flushText(plain, spans);
                spans.add(new Strong(parseInline(input.substring(index + 2, end))));
                index = end + 2;
                continue;
            }
            if (current == '*') {
                int end = input.indexOf('*', index + 1);
                if (end < 0) {
                    plain.append(current);
                    index++;
                    continue;
                }
                flushText(plain, spans);
                spans.add(new Emphasis(parseInline(input.substring(index + 1, end))));
                index = end + 1;
                continue;
            }
            plain.append(current);
            index++;
        }
        flushText(plain, spans);
        return List.copyOf(spans);
    }

    private static void flushText(StringBuilder plain, List<Span> spans) {
        if (plain.length() > 0) {
            spans.add(new Text(plain.toString()));
            plain.setLength(0);
        }
    }

    /** The plain text of spans, used to rejoin wrapped list lines. */
    static String plainText(List<Span> spans) {
        StringBuilder text = new StringBuilder();
        for (Span span : spans) {
            if (span instanceof Text typed) {
                text.append(typed.text());
            } else if (span instanceof Code typed) {
                text.append('`').append(typed.text()).append('`');
            } else if (span instanceof Strong typed) {
                text.append("**").append(plainText(typed.inner())).append("**");
            } else if (span instanceof Emphasis typed) {
                text.append('*').append(plainText(typed.inner())).append('*');
            } else if (span instanceof Link typed) {
                text.append(plainText(typed.inner()));
            }
        }
        return text.toString();
    }

    /** The GitHub style anchor of a heading, used for in-guide links. */
    static String anchorOf(Heading heading) {
        String text = plainText(heading.spans()).toLowerCase(java.util.Locale.ROOT);
        StringBuilder anchor = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                anchor.append(current);
            } else if (current == ' ' || current == '-') {
                anchor.append('-');
            }
        }
        return anchor.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
    }
}
