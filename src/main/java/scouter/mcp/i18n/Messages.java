package scouter.mcp.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Locale-aware lookup for dynamic, user-facing messages (tool error text, result hints, notes).
 * Backed by messages.properties (English base) and messages_ko.properties (Korean override).
 * Structured stderr logs and static schema/tool descriptions are NOT localized.
 */
public final class Messages {

    private static final String BUNDLE = "messages";

    private Messages() {
    }

    /**
     * Resolve a message for the given locale and format it with positional arguments
     * ({0}, {1}, ...). Falls back to the English base bundle for missing keys/locales.
     * If the key itself is missing, returns the key so failures are visible, not silent.
     */
    public static String get(Locale locale, String key, Object... args) {
        Locale loc = locale != null ? locale : Locale.ENGLISH;
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, loc);
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (Exception e) {
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        // MessageFormat is locale-sensitive for number formatting; pin to the same locale.
        return new MessageFormat(pattern, loc).format(args);
    }
}
