package com.example.fhirviewer.server;

/**
 * The paging links a search {@code Bundle} carried.
 *
 * <p>A FHIR search result describes its own navigation: {@code self}, {@code first},
 * {@code previous}, {@code next} and {@code last}, each an absolute URL the server
 * minted. Only the server knows what state a page token encodes and what ordering it
 * assumed, so these URLs are used exactly as given and never rebuilt from the search
 * that started it. Reconstructing {@code ?_getpages=...} by hand is how a viewer ends
 * up showing the same page twice, or the last page twice, on a server that counts
 * differently.
 *
 * <p>Which links appear is entirely up to the server. A first page normally carries
 * {@code self}, {@code next} and sometimes {@code last}; a middle page may add
 * {@code previous} and {@code first}. Any of them may be absent at any time, so every
 * accessor is nullable and every {@code hasX} answers honestly.
 *
 * <p>This is immutable data with no URL handling: keeping the strings exactly as the
 * server sent them is the point, and any joining or encoding belongs to the transport
 * that requests them.
 *
 * @param self     the URL of the page that was returned, or {@code null}
 * @param first    the URL of the first page, or {@code null}
 * @param previous the URL of the page before this one, or {@code null}
 * @param next     the URL of the page after this one, or {@code null}
 * @param last     the URL of the last page, or {@code null}
 */
public record SearchPageLinks(String self, String first, String previous, String next, String last) {

    public SearchPageLinks {
        self = blankToNull(self);
        first = blankToNull(first);
        previous = blankToNull(previous);
        next = blankToNull(next);
        last = blankToNull(last);
    }

    /** No links at all: a server that returned a Bundle without any, which is legal. */
    public static SearchPageLinks none() {
        return new SearchPageLinks(null, null, null, null, null);
    }

    /** The server-provided URL of the next page, or {@code null}. */
    public String next() {
        return next;
    }

    /** The server-provided URL of the previous page, or {@code null}. */
    public String previous() {
        return previous;
    }

    /** The server-provided URL of the first page, or {@code null}. */
    public String first() {
        return first;
    }

    /** The server-provided URL of the last page, or {@code null}. */
    public String last() {
        return last;
    }

    public boolean hasNext() {
        return next != null;
    }

    public boolean hasPrevious() {
        return previous != null;
    }

    public boolean hasFirst() {
        return first != null;
    }

    public boolean hasLast() {
        return last != null;
    }

    public boolean isEmpty() {
        return self == null && first == null && previous == null && next == null && last == null;
    }

    /** Counts the links for a status line; never prints a URL, which can carry a token. */
    public String describe() {
        if (isEmpty()) {
            return "no paging links";
        }
        StringBuilder text = new StringBuilder();
        append(text, "self", self);
        append(text, "first", first);
        append(text, "previous", previous);
        append(text, "next", next);
        append(text, "last", last);
        return text.toString();
    }

    private static void append(StringBuilder text, String name, String url) {
        if (url != null) {
            text.append(text.isEmpty() ? "" : ", ").append(name);
        }
    }

    /**
     * A blank link is no link.
     *
     * <p>Servers do emit empty {@code link.url} elements, and a caller following one
     * would otherwise request the current page again and look like a paging loop.
     */
    private static String blankToNull(String url) {
        return url == null || url.isBlank() ? null : url.trim();
    }

    /** Never prints the URLs: a page token can identify a patient's result set. */
    @Override
    public String toString() {
        return "SearchPageLinks[" + describe() + "]";
    }
}
