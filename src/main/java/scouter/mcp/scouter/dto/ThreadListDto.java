package scouter.mcp.scouter.dto;

import java.util.List;
import java.util.Map;

/** Per-instance thread list: full state histogram + top rows by cpu (bounded by Limits.THREAD_MAX_ROWS). */
public record ThreadListDto(int objHash, String objName, int totalThreads,
                            Map<String, Integer> stateCounts, List<ThreadRowDto> threads, boolean truncated) {
}
