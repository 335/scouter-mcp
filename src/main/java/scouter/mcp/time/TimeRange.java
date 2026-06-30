package scouter.mcp.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeRange {

    private static final Pattern RELATIVE = Pattern.compile("^now(?:-(\\d+)([smhd]))?$");

    private TimeRange() {
    }

    public static long parseInstant(String expr, ZoneId zone, long nowMillis) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("time expression is blank");
        }
        String v = expr.trim();
        Matcher m = RELATIVE.matcher(v);
        if (m.matches()) {
            if (m.group(1) == null) {
                return nowMillis;
            }
            long amount = Long.parseLong(m.group(1));
            long unitMs = switch (m.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> throw new IllegalArgumentException("unsupported unit: " + m.group(2));
            };
            return nowMillis - amount * unitMs;
        }
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return java.time.LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("unrecognized time expression: " + expr);
        }
    }

    public static String yyyymmdd(long epochMillis, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    public static String toIso(long epochMillis, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
    }
}
