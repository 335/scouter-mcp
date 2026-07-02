package scouter.mcp.policy;

/** Response-size guard: cut oversized text with an explicit marker instead of feeding it to the LLM whole. */
public final class Truncate {

    private Truncate() {
    }

    /** Cut s to max chars, appending a marker carrying the original length. Null-safe. */
    public static String text(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(truncated, " + s.length() + " chars total)";
    }
}
