package scouter.mcp.scouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes a sloppily-typed service query into something the collector can match.
 * Scouter service names look like "/api/order/.../search-order-info-grade&lt;POST&gt;", but users
 * type "GET orderDetail", "order info grade", or paste the exact name — they don't know the format.
 * StrMatch (the collector-side matcher) is case-sensitive and supports at most two wildcard
 * segments, so this produces the safest best-effort server pattern plus lowercase tokens for the
 * client-side candidate discovery that runs when the server pattern matches nothing.
 */
public final class ServiceQueryNormalizer {

    /** serverPattern: StrMatch pattern sent to the collector. method: extracted HTTP method or null.
     *  tokens: lowercase alphanumeric fragments of the path part, for client-side fuzzy matching. */
    public record ServiceQuery(String serverPattern, String method, List<String> tokens) {
    }

    private static final Pattern ANGLE_METHOD = Pattern.compile("<(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDGE_METHOD = Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\b|\\b(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)$", Pattern.CASE_INSENSITIVE);

    private ServiceQueryNormalizer() {
    }

    public static ServiceQuery normalize(String raw) {
        String input = raw == null ? "" : raw.trim();

        // Explicit wildcard: an advanced pattern — pass through untouched (no method extraction).
        if (input.indexOf('*') >= 0) {
            return new ServiceQuery(input, null, tokensOf(input));
        }

        String method = null;
        String path = input;

        Matcher angle = ANGLE_METHOD.matcher(path);
        if (angle.find()) {
            method = angle.group(1).toUpperCase(Locale.ROOT);
            path = angle.replaceAll("").trim();
        } else {
            Matcher edge = EDGE_METHOD.matcher(path);
            if (edge.find()) {
                String m = edge.group(1) != null ? edge.group(1) : edge.group(2);
                // Only treat it as a method when it is a standalone word among others, not the whole input.
                String stripped = edge.replaceAll("").trim();
                if (!stripped.isEmpty()) {
                    method = m.toUpperCase(Locale.ROOT);
                    path = stripped;
                }
            }
        }

        // Whitespace never occurs inside a service URL: pick the longest whitespace-separated token
        // as the server-side needle (StrMatch is case-sensitive; keep the user's casing as typed).
        String needle = path;
        String[] words = path.split("\\s+");
        if (words.length > 1) {
            needle = words[0];
            for (String w : words) {
                if (w.length() > needle.length()) {
                    needle = w;
                }
            }
        }

        String pattern = method != null ? "*" + needle + "*<" + method + ">" : "*" + needle + "*";
        return new ServiceQuery(pattern, method, tokensOf(path));
    }

    private static List<String> tokensOf(String path) {
        List<String> tokens = new ArrayList<>();
        for (String t : path.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        return tokens;
    }
}
