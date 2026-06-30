package scouter.mcp.scouter.dto;

public record XLogRowDto(
        long txid, long gxid, int objHash, String objName,
        String service, int elapsedMs, String error,
        int cpuMs, int sqlCount, long endTimeMillis, String endTimeIso) {
}
