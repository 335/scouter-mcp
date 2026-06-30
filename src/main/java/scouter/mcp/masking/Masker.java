package scouter.mcp.masking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Masker {

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE =
            Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}");
    private static final Pattern LONG_DIGITS =
            Pattern.compile("\\b\\d{13,16}\\b");
    private static final Pattern SECRET_KV =
            Pattern.compile("(?i)(password|passwd|pwd|token|secret)\\s*[=:]\\s*\\S+");

    public String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String s = input;
        s = maskMatches(SECRET_KV, s, m -> {
            String full = m.group();
            int sep = Math.max(full.indexOf('='), full.indexOf(':'));
            return full.substring(0, sep + 1) + "****";
        });
        s = maskMatches(EMAIL, s, m -> "****@****");
        s = maskMatches(LONG_DIGITS, s, m -> "*".repeat(m.group().length()));
        s = maskMatches(PHONE, s, m -> "***-****-****");
        return s;
    }

    private interface Repl {
        String apply(Matcher m);
    }

    private static String maskMatches(Pattern p, String s, Repl repl) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(repl.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
