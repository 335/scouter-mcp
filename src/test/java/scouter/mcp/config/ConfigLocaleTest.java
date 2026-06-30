package scouter.mcp.config;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLocaleTest {

    @Test
    void explicitEnvWins() {
        assertThat(Config.resolveLocale("ko")).isEqualTo(Locale.KOREAN);
        assertThat(Config.resolveLocale("KO")).isEqualTo(Locale.KOREAN);
        assertThat(Config.resolveLocale("en")).isEqualTo(Locale.ENGLISH);
        // Any other explicit value (unsupported) resolves to English.
        assertThat(Config.resolveLocale("fr")).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void blankFallsBackToJvmDefaultLanguage() {
        // Blank/null -> derive from JVM default: Korean only if the JVM language is Korean.
        Locale expected = "ko".equalsIgnoreCase(Locale.getDefault().getLanguage())
                ? Locale.KOREAN : Locale.ENGLISH;
        assertThat(Config.resolveLocale(null)).isEqualTo(expected);
        assertThat(Config.resolveLocale("  ")).isEqualTo(expected);
    }

    @Test
    void fromEnvExposesLocale() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "p", "SCOUTER_LOCALE", "ko"));
        assertThat(c.locale()).isEqualTo(Locale.KOREAN);
    }
}
