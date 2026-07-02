package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.lang.value.ListValue;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackMapperTest {

    @Test
    void mapsTimeValueListsToPoints() {
        ListValue time = new ListValue();
        time.add(1000L);
        time.add(2000L);
        ListValue value = new ListValue();
        value.add(1.5d);
        value.add(2.5d);

        List<PackMapper.Point> points = PackMapper.toPoints(time, value);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).timeMillis()).isEqualTo(1000L);
        assertThat(points.get(0).value()).isEqualTo(1.5d);
        assertThat(points.get(1).timeMillis()).isEqualTo(2000L);
        assertThat(points.get(1).value()).isEqualTo(2.5d);
    }

    @Test
    void handlesNullListsAsEmpty() {
        assertThat(PackMapper.toPoints(null, null)).isEmpty();
    }

    @Test
    void downsampleKeepsSmallSeriesUnchanged() {
        List<PackMapper.Point> pts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pts.add(new PackMapper.Point(i, i));
        }
        assertThat(PackMapper.downsample(pts, 360)).isSameAs(pts);
    }

    @Test
    void downsampleReducesLargeSeriesAndPreservesShape() {
        List<PackMapper.Point> pts = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            pts.add(new PackMapper.Point(i, i)); // ramp 0..9999
        }
        List<PackMapper.Point> ds = PackMapper.downsample(pts, 100);
        assertThat(ds).hasSize(100);
        // min/max downsampling: on a monotonic ramp the first bucket's min is 0, the last bucket's max is 9999.
        assertThat(ds.get(0).value()).isEqualTo(0.0d);
        assertThat(ds.get(99).value()).isEqualTo(9999.0d);
        // Timestamps are non-decreasing
        assertThat(ds.get(0).timeMillis()).isLessThan(ds.get(99).timeMillis());
    }

    @Test
    void downsamplePreservesSpikes() {
        List<PackMapper.Point> pts = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            pts.add(new PackMapper.Point(i, 1.0d)); // flat baseline
        }
        pts.set(5_000, new PackMapper.Point(5_000, 999.0d)); // single sharp spike
        List<PackMapper.Point> ds = PackMapper.downsample(pts, 100);
        double max = ds.stream().mapToDouble(PackMapper.Point::value).max().orElse(0);
        // Bucket averaging would smear the spike to ~11; min/max downsampling must keep the true peak.
        assertThat(max).isEqualTo(999.0d);
    }

    @Test
    void downsampleStaysWithinMaxPoints() {
        List<PackMapper.Point> pts = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            pts.add(new PackMapper.Point(i, i % 7)); // varying so min != max per bucket
        }
        assertThat(PackMapper.downsample(pts, 100)).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void statsComputedFromFullSeries() {
        List<PackMapper.Point> pts = List.of(
                new PackMapper.Point(1, 2.0d),
                new PackMapper.Point(2, 4.0d),
                new PackMapper.Point(3, 6.0d));
        PackMapper.SeriesStats st = PackMapper.stats(pts);
        assertThat(st.count()).isEqualTo(3);
        assertThat(st.min()).isEqualTo(2.0d);
        assertThat(st.max()).isEqualTo(6.0d);
        assertThat(st.avg()).isEqualTo(4.0d);
        assertThat(st.first()).isEqualTo(2.0d);
        assertThat(st.last()).isEqualTo(6.0d);
    }

    @Test
    void statsOnEmptyIsZeroCount() {
        PackMapper.SeriesStats st = PackMapper.stats(List.of());
        assertThat(st.count()).isZero();
        assertThat(st.first()).isNull();
    }

    @Test
    void clampsToShorterListLength() {
        ListValue time = new ListValue();
        time.add(1000L);
        time.add(2000L);
        ListValue value = new ListValue();
        value.add(1.5d);

        List<PackMapper.Point> points = PackMapper.toPoints(time, value);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).timeMillis()).isEqualTo(1000L);
    }
}
