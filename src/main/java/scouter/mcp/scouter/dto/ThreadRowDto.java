package scouter.mcp.scouter.dto;

/** One JVM thread row (OBJECT_THREAD_LIST). txid/elapsedMs/service are set only while a service runs on it. */
public record ThreadRowDto(long id, String name, String state, long cpuMs,
                           String txid, Long elapsedMs, String service) {
}
