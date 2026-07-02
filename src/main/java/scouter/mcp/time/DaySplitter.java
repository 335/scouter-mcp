package scouter.mcp.time;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an epoch-millis range [from, to] into per-calendar-day segments in a given zone.
 * The Scouter collector stores XLogs and counters in per-day partitions keyed by yyyyMMdd, so a
 * window straddling midnight must be queried day by day (each segment carries the correct DATE),
 * otherwise data on one side of midnight is silently lost. Segments are contiguous and
 * non-overlapping: intermediate segments end at the millisecond before the next day's midnight,
 * and the last segment ends exactly at {@code to}.
 */
public final class DaySplitter {

    private DaySplitter() {
    }

    public record Segment(long fromMillis, long toMillis) {
    }

    public static List<Segment> splitByCalendarDay(long fromMillis, long toMillis, ZoneId zone) {
        List<Segment> out = new ArrayList<>();
        long cursor = fromMillis;
        while (cursor < toMillis) {
            long dayEndExclusive = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                    .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long segTo = Math.min(dayEndExclusive - 1, toMillis);
            out.add(new Segment(cursor, segTo));
            cursor = dayEndExclusive;
        }
        return out;
    }
}
