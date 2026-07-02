package scouter.mcp.scouter.dto;

/** One row of a daily summary (service/sql/apiCall: full metrics; ip/userAgent: name+count only). */
public record SummaryRowDto(String name, long count, Long errorCount, Long totalElapsedMs, Double avgMs) {
}
