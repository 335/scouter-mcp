package scouter.mcp.scouter;

import scouter.lang.value.ListValue;

import java.util.ArrayList;
import java.util.List;

public final class PackMapper {

    private PackMapper() {
    }

    public record Point(long timeMillis, double value) {
    }

    public static List<Point> toPoints(ListValue time, ListValue value) {
        List<Point> out = new ArrayList<>();
        if (time == null || value == null) {
            return out;
        }
        int n = Math.min(time.size(), value.size());
        for (int i = 0; i < n; i++) {
            out.add(new Point(time.getLong(i), value.getDouble(i)));
        }
        return out;
    }
}
