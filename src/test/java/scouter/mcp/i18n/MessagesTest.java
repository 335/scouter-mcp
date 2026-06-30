package scouter.mcp.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    @Test
    void resolvesEnglishAndKorean() {
        assertThat(Messages.get(Locale.ENGLISH, "error.counter_obj_required"))
                .isEqualTo("Either objHashes or objType is required");
        assertThat(Messages.get(Locale.KOREAN, "error.counter_obj_required"))
                .contains("objHashes")
                .contains("objType");
        // The two locales must produce different text for the same key.
        assertThat(Messages.get(Locale.ENGLISH, "error.counter_obj_required"))
                .isNotEqualTo(Messages.get(Locale.KOREAN, "error.counter_obj_required"));
    }

    @Test
    void formatsPositionalArguments() {
        assertThat(Messages.get(Locale.ENGLISH, "error.window_too_long", 24))
                .contains("24");
        // note has two args: {0}=cap, {1}=total
        String note = Messages.get(Locale.ENGLISH, "note.counter_obj_truncated", 20, 57);
        assertThat(note).contains("20").contains("57");
    }

    @Test
    void unknownLocaleFallsBackToEnglishBase() {
        // French is unsupported -> falls back to the base (English) bundle, not the key.
        assertThat(Messages.get(Locale.FRENCH, "hint.search_empty"))
                .isEqualTo(Messages.get(Locale.ENGLISH, "hint.search_empty"));
    }

    @Test
    void missingKeyReturnsKeyItself() {
        assertThat(Messages.get(Locale.ENGLISH, "no.such.key")).isEqualTo("no.such.key");
    }

    @Test
    void nullLocaleDefaultsToEnglish() {
        assertThat(Messages.get(null, "hint.gxid_empty"))
                .isEqualTo(Messages.get(Locale.ENGLISH, "hint.gxid_empty"));
    }
}
