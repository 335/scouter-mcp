package scouter.mcp.scouter.dto;

/** A currently-running service/thread on an agent (from OBJECT_ACTIVE_SERVICE_LIST). */
public record ActiveServiceDto(
        long id, String threadName, String state, long cpu, String txid,
        String service, String ip, long elapsedMs, String sql, String subcall,
        String login, String desc) {
}
