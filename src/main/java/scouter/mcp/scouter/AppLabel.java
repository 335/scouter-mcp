package scouter.mcp.scouter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Derives a human-meaningful short label from a Scouter objName. k8s-style objNames look like
 * "/&lt;app&gt;-deployment-&lt;rs-hash&gt;-&lt;pod-suffix&gt;/&lt;agent&gt;": the trailing hashes
 * change every deploy and identify nothing to a human, while the leading words ARE the identity.
 * Models summarizing long names tend to cut from the front and keep the hash — producing labels
 * like "deployment-6576f86784" that are indistinguishable. Pre-computing app/instance here lets
 * tool output carry the right label so the model never has to truncate on its own.
 */
public final class AppLabel {

    /** app: leading identity words. instance: the stripped hash/ordinal tokens (null if none). */
    public record Label(String app, String instance) {
    }

    private AppLabel() {
    }

    public static Label of(String objName) {
        if (objName == null || objName.isBlank()) {
            return new Label(null, null);
        }
        String first = objName.startsWith("/") ? objName.substring(1) : objName;
        int slash = first.indexOf('/');
        if (slash >= 0) {
            first = first.substring(0, slash);
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(first.split("-")));
        List<String> stripped = new ArrayList<>();

        // k8s Deployment pod: ...-<rs-hash(7-12, has digit)>-<pod-suffix(4-6 alnum)>
        if (tokens.size() >= 3
                && tokens.get(tokens.size() - 1).matches("[a-z0-9]{4,6}")
                && isHashToken(tokens.get(tokens.size() - 2), 7, 12)) {
            stripped.add(0, tokens.remove(tokens.size() - 1));
            stripped.add(0, tokens.remove(tokens.size() - 1));
        } else if (tokens.size() >= 2) {
            String last = tokens.get(tokens.size() - 1);
            // StatefulSet ordinal ("-0") or a single trailing hash token.
            if (last.matches("\\d+") || isHashToken(last, 4, 12)) {
                stripped.add(0, tokens.remove(tokens.size() - 1));
            }
        }
        // "deployment" as a trailing token adds nothing once the hashes are gone.
        if (!stripped.isEmpty() && tokens.size() >= 2
                && (tokens.get(tokens.size() - 1).equals("deployment") || tokens.get(tokens.size() - 1).equals("deploy"))) {
            tokens.remove(tokens.size() - 1);
        }

        String app = String.join("-", tokens);
        String instance = stripped.isEmpty() ? null : String.join("-", stripped);
        return new Label(app.isBlank() ? null : app, instance);
    }

    // A hash token is lowercase alphanumeric with at least one digit (pure words are identity, not hashes).
    private static boolean isHashToken(String t, int min, int max) {
        return t.length() >= min && t.length() <= max
                && t.matches("[a-z0-9]+") && t.chars().anyMatch(Character::isDigit);
    }
}
