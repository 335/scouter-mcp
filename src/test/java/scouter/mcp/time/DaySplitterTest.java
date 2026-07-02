package scouter.mcp.time;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DaySplitterTest {

    private final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 2026-06-29T00:00:00+09:00
    private static final long JUN29_MIDNIGHT = 1782658800000L;
    private static final long DAY_MS = 86_400_000L;
    private static final long JUN30_MIDNIGHT = JUN29_MIDNIGHT + DAY_MS;

    @Test
    void singleSegmentWhenWithinSameDay() {
        long from = JUN29_MIDNIGHT + 10L * 3_600_000; // 10:00
        long to = JUN29_MIDNIGHT + 11L * 3_600_000;   // 11:00
        List<DaySplitter.Segment> segs = DaySplitter.splitByCalendarDay(from, to, KST);
        assertThat(segs).containsExactly(new DaySplitter.Segment(from, to));
    }

    @Test
    void splitsAcrossMidnightIntoTwoSegments() {
        long from = JUN29_MIDNIGHT + 23L * 3_600_000; // 06-29 23:00
        long to = JUN30_MIDNIGHT + 1L * 3_600_000;    // 06-30 01:00
        List<DaySplitter.Segment> segs = DaySplitter.splitByCalendarDay(from, to, KST);
        assertThat(segs).containsExactly(
                new DaySplitter.Segment(from, JUN30_MIDNIGHT - 1),
                new DaySplitter.Segment(JUN30_MIDNIGHT, to));
    }

    @Test
    void splitsMultipleFullDays() {
        long from = JUN29_MIDNIGHT + 12L * 3_600_000;       // 06-29 12:00
        long to = JUN29_MIDNIGHT + 2 * DAY_MS + 3_600_000;  // 07-01 01:00
        List<DaySplitter.Segment> segs = DaySplitter.splitByCalendarDay(from, to, KST);
        assertThat(segs).containsExactly(
                new DaySplitter.Segment(from, JUN30_MIDNIGHT - 1),
                new DaySplitter.Segment(JUN30_MIDNIGHT, JUN29_MIDNIGHT + 2 * DAY_MS - 1),
                new DaySplitter.Segment(JUN29_MIDNIGHT + 2 * DAY_MS, to));
    }

    @Test
    void segmentsAreContiguousAndNonOverlapping() {
        long from = JUN29_MIDNIGHT + 23L * 3_600_000;
        long to = JUN30_MIDNIGHT + 5L * 3_600_000;
        List<DaySplitter.Segment> segs = DaySplitter.splitByCalendarDay(from, to, KST);
        assertThat(segs.get(0).fromMillis()).isEqualTo(from);
        assertThat(segs.get(segs.size() - 1).toMillis()).isEqualTo(to);
        for (int i = 1; i < segs.size(); i++) {
            assertThat(segs.get(i).fromMillis()).isEqualTo(segs.get(i - 1).toMillis() + 1);
        }
    }
}
