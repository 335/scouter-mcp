package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.lang.value.ListValue;

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
