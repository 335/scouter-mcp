package scouter.mcp.scouter.dto;

import java.util.List;

/**
 * get_summary result: exactly one of rows/errorRows/alertRows is non-null depending on the category.
 * timedOut: the per-instance fan-out deadline expired (or an instance read-timed-out) before every
 * pass finished, so the aggregate is partial — target a single objHash or shorten the range.
 */
public record SummaryResult(String category, int totalRows, boolean truncated,
                            List<SummaryRowDto> rows,
                            List<ErrorSummaryRowDto> errorRows,
                            List<AlertSummaryRowDto> alertRows,
                            boolean timedOut) {
}
