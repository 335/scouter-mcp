package scouter.mcp.time;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeRangeTest {

    private final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    void parsesIso8601ToEpochMillis() {
        long ms = TimeRange.parseInstant("2026-06-29T00:00:00+09:00", KST, 1_000_000L);
        assertThat(ms).isEqualTo(1782658800000L); // 2026-06-29T00:00:00+09:00
    }

    @Test
    void parsesRelativeExpressionAgainstNow() {
        long now = 1_000_000_000_000L;
        assertThat(TimeRange.parseInstant("now", KST, now)).isEqualTo(now);
        assertThat(TimeRange.parseInstant("now-1h", KST, now)).isEqualTo(now - 3_600_000L);
        assertThat(TimeRange.parseInstant("now-30m", KST, now)).isEqualTo(now - 1_800_000L);
        assertThat(TimeRange.parseInstant("now-2d", KST, now)).isEqualTo(now - 2L * 86_400_000L);
    }

    @Test
    void rejectsUnknownFormat() {
        assertThatThrownBy(() -> TimeRange.parseInstant("yesterday", KST, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsYyyymmddInZone() {
        assertThat(TimeRange.yyyymmdd(1782658800000L, KST)).isEqualTo("20260629");
    }

    @Test
    void formatsIsoOutput() {
        assertThat(TimeRange.toIso(1782658800000L, KST)).isEqualTo("2026-06-29T00:00:00+09:00");
    }
}
