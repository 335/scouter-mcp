package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PercentilesTest {

    @Test
    void p95OfOneToHundredIs95() {
        List<Integer> vals = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            vals.add(i);
        }
        assertThat(Percentiles.nearestRank(vals, 95)).isEqualTo(95);
    }

    @Test
    void p50IsMedianByNearestRank() {
        List<Integer> vals = List.of(10, 20, 30, 40);
        // nearest-rank p50: ceil(0.5*4)=2 -> 2nd smallest = 20
        assertThat(Percentiles.nearestRank(vals, 50)).isEqualTo(20);
    }

    @Test
    void singleValue() {
        assertThat(Percentiles.nearestRank(List.of(42), 95)).isEqualTo(42);
    }

    @Test
    void emptyIsZero() {
        assertThat(Percentiles.nearestRank(List.of(), 95)).isEqualTo(0);
    }

    @Test
    void handlesUnsortedInput() {
        assertThat(Percentiles.nearestRank(List.of(50, 10, 90, 30, 70), 100)).isEqualTo(90);
    }
}
