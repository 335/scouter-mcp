package scouter.mcp.scouter.dto;

/**
 * txidStr / gxidStr: Hexa32 (radix-32) encoding of txid/gxid, matching what the Scouter Eclipse
 * client displays (e.g. "z3st744n3d2p6q"). Users can paste either the raw long or the Hexa32
 * string into get_xlog_detail / get_xlog_by_gxid; both are accepted.
 * gxidStr is null when gxid == 0 (single-service transaction, no distributed context).
 */
public record XLogRowDto(
        long txid, String txidStr,
        long gxid, String gxidStr,
        int objHash, String objName,
        String service, int elapsedMs, String error,
        int cpuMs, int sqlCount, long endTimeMillis, String endTimeIso) {
}
