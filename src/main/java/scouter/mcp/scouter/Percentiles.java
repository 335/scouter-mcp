package scouter.mcp.scouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Nearest-rank percentile over integer samples (e.g. elapsed times), used by service aggregation. */
public final class Percentiles {

    private Percentiles() {
    }

    public static int nearestRank(List<Integer> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        int rank = (int) Math.ceil(percentile / 100.0 * n);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        return sorted.get(rank - 1);
    }
}
