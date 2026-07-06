package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mergeCapped concatenates per-pass results (a fuzzy target fans out over instances/day-segments) in
 * pass order and caps the total, so the global row limit stays deterministic under parallel fan-out.
 */
class MergeCappedTest {

    @Test
    void concatenatesPartsInPassOrder() {
        List<Integer> out = TcpScouterClient.mergeCapped(
                List.of(List.of(1, 2), List.of(3), List.of(4, 5)), 100);
        assertThat(out).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void capsAcrossPartsAndStopsEarly() {
        List<Integer> out = TcpScouterClient.mergeCapped(
                List.of(List.of(1, 2, 3), List.of(4, 5), List.of(6)), 4);
        assertThat(out).containsExactly(1, 2, 3, 4);
    }

    @Test
    void capBoundaryExactlyAtPartEnd() {
        List<Integer> out = TcpScouterClient.mergeCapped(
                List.of(List.of(1, 2), List.of(3, 4)), 2);
        assertThat(out).containsExactly(1, 2);
    }

    @Test
    void handlesEmptyPartsAndEmptyInput() {
        assertThat(TcpScouterClient.mergeCapped(List.<List<Integer>>of(), 10)).isEmpty();
        assertThat(TcpScouterClient.mergeCapped(List.of(List.of(), List.of(1), List.of()), 10))
                .containsExactly(1);
    }
}
