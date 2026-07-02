package scouter.mcp.config;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {

    @Test
    void loadsRequiredFieldsFromEnvMap() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "collector",
                "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "admin",
                "SCOUTER_PASSWORD", "secret"));
        assertThat(c.host()).isEqualTo("collector");
        assertThat(c.port()).isEqualTo(6100);
        assertThat(c.user()).isEqualTo("admin");
        assertThat(c.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void failsWhenRequiredMissing() {
        assertThatThrownBy(() -> Config.fromEnv(Map.of("SCOUTER_COLLECTOR_HOST", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SCOUTER_COLLECTOR_PORT");
    }

    @Test
    void toStringNeverLeaksPassword() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "topsecret"));
        assertThat(c.toString()).doesNotContain("topsecret");
    }

    @Test
    void bindParamsEnabledByDefault() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "p"));
        assertThat(c.bindParamsEnabled()).isTrue();
    }

    @Test
    void bindParamsDisabledWhenEnvFalse() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "p",
                "SCOUTER_INCLUDE_BIND_PARAMS", "false"));
        assertThat(c.bindParamsEnabled()).isFalse();
    }

    @Test
    void overridesTimezone() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "p", "SCOUTER_TZ", "UTC"));
        assertThat(c.zone()).isEqualTo(ZoneId.of("UTC"));
    }
}
