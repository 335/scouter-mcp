package scouter.mcp.scouter.dto;

/** Aggregated per-service XLog statistics over a window (no raw rows retained). */
public record ServiceSummaryDto(
        String service, long count, long errorCount, double errorRate,
        double avgMs, long maxMs, long p95Ms) {
}
