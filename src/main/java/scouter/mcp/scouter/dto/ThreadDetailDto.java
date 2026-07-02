package scouter.mcp.scouter.dto;

/**
 * Live thread detail (OBJECT_THREAD_DETAIL). The java agent locates the thread via the ACTIVE txid;
 * when the transaction already finished it returns state="end" with no stack. Numeric time fields are
 * as reported by the agent. sqlBindVar is cleared when bind params are disabled by the operator.
 */
public record ThreadDetailDto(int objHash, String objName,
                              String threadName, Long threadId, String state, String stackTrace,
                              Long cpuTime, Long userTime,
                              Long blockedCount, Long blockedTime, Long waitedCount, Long waitedTime,
                              String lockName, Long lockOwnerId, String lockOwnerName,
                              String serviceTxid, String serviceName, Long serviceElapsedMs,
                              String sql, String sqlBindVar, String subcall) {
}
