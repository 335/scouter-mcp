package scouter.mcp.scouter;

/**
 * Reproduces the Hexa32 txid/gxid string encoding used by the Scouter Eclipse client
 * (scouter.util.Hexa32#toString32 / toLong32).
 *
 * Encoding: Java Long.toString(n, 32) with charset '0'-'9','a'-'v'.
 *   Negative  -> 'z' prefix + abs(n) in base-32   e.g. -4926643480458686894 -> "z48nnk4idrnsde"
 *   Positive  -> '+' prefix + n in base-32         e.g.  7283809205160911224 -> "+6a5a9s5ulc8bo"
 *   [0,9]     -> plain decimal                     e.g.  3                   -> "3"
 *   MIN_VALUE -> "z8000000000000"   (special-cased to avoid overflow on negation)
 *
 * This lets users paste the string they see in the Scouter client directly into tool arguments,
 * and lets the MCP output the same representation alongside the raw long for cross-referencing.
 */
public final class Hexa32 {

    private static final String MIN_VALUE_STR = "z8000000000000";

    private Hexa32() {
    }

    /** Convert a signed long txid/gxid to the Scouter client display string. */
    public static String toString32(long num) {
        if (num == Long.MIN_VALUE) {
            return MIN_VALUE_STR;
        }
        if (num < 0) {
            return "z" + Long.toString(-num, 32);
        }
        if (num < 10) {
            return Long.toString(num);
        }
        return "+" + Long.toString(num, 32);
    }

    /**
     * Parse a txid/gxid from either:
     * - a Scouter client display string ("z...", "+...", or plain decimal)
     * - a plain decimal string (positive or negative long)
     *
     * Returns the signed long value, or throws IllegalArgumentException on bad input.
     */
    public static long toLong(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("txid/gxid must not be empty");
        }
        char first = s.charAt(0);
        if (first == 'z') {
            if (s.equals(MIN_VALUE_STR)) {
                return Long.MIN_VALUE;
            }
            if (s.length() < 2) {
                throw new IllegalArgumentException("invalid Hexa32 txid: " + s);
            }
            return -Long.parseLong(s.substring(1), 32);
        }
        if (first == '+') {
            if (s.length() < 2) {
                throw new IllegalArgumentException("invalid Hexa32 txid: " + s);
            }
            return Long.parseLong(s.substring(1), 32);
        }
        // Plain decimal (the raw long stored by the MCP, or a small non-negative value).
        return Long.parseLong(s);
    }

    /** Return true if the string looks like a Hexa32 txid/gxid (starts with 'z' or '+'). */
    public static boolean isHexa32(String s) {
        return s != null && !s.isEmpty() && (s.charAt(0) == 'z' || s.charAt(0) == '+');
    }
}
