package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.lang.pack.MapPack;
import scouter.lang.value.ListValue;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure merge-logic tests for the LOAD_*_SUMMARY columnar packs (no network). */
class SummaryMergeTest {

    private static MapPack sqlDay(int[] ids, int[] counts, int[] errors, long[] elapseds) {
        MapPack mp = new MapPack();
        ListValue id = mp.newList("id");
        ListValue count = mp.newList("count");
        ListValue error = mp.newList("error");
        ListValue elapsed = mp.newList("elapsed");
        for (int i = 0; i < ids.length; i++) {
            id.add(ids[i]);
            count.add(counts[i]);
            error.add(errors[i]);
            elapsed.add(elapseds[i]);
        }
        return mp;
    }

    @Test
    void mergesSameSqlIdAcrossDays() {
        Map<String, TcpScouterClient.SumAcc> acc = new LinkedHashMap<>();

        TcpScouterClient.mergeSummaryDay("sql", sqlDay(
                new int[]{7, 8}, new int[]{10, 5}, new int[]{1, 0}, new long[]{100, 50}), 20260701L, acc);
        TcpScouterClient.mergeSummaryDay("sql", sqlDay(
                new int[]{7}, new int[]{20}, new int[]{2}, new long[]{300}), 20260702L, acc);

        assertThat(acc).hasSize(2);
        TcpScouterClient.SumAcc a7 = acc.get("7");
        assertThat(a7.count).isEqualTo(30);
        assertThat(a7.error).isEqualTo(3);
        assertThat(a7.elapsed).isEqualTo(400);
        assertThat(a7.anyYmd).isEqualTo(20260701L); // first day seen wins (text resolution day)
    }

    @Test
    void errorCategoryKeysByErrorAndServiceAndKeepsSampleTxid() {
        MapPack mp = new MapPack();
        ListValue id = mp.newList("id");
        ListValue error = mp.newList("error");
        ListValue service = mp.newList("service");
        ListValue message = mp.newList("message");
        ListValue count = mp.newList("count");
        ListValue txid = mp.newList("txid");
        // same error hash on two different services -> two rows
        id.add(1); error.add(500); service.add(11); message.add(71); count.add(3); txid.add(1234L);
        id.add(2); error.add(500); service.add(22); message.add(72); count.add(4); txid.add(5678L);

        Map<String, TcpScouterClient.SumAcc> acc = new LinkedHashMap<>();
        TcpScouterClient.mergeSummaryDay("error", mp, 20260703L, acc);

        assertThat(acc).hasSize(2);
        TcpScouterClient.SumAcc first = acc.values().iterator().next();
        assertThat(first.count).isEqualTo(3);
        assertThat(first.error).isEqualTo(500); // text hash, not additive
        assertThat(first.serviceHash).isEqualTo(11);
        assertThat(first.sampleTxid).isEqualTo(1234L);
    }
}
