package scouter.mcp.scouter.dto;

import java.util.List;

/**
 * search_xlog result + truncation signal.
 * truncated: more results may exist because the limit or scan cap was hit.
 * scanCapReached: stopped because the scan cap (Limits.SEARCH_SCAN_CAP) was reached (a signal to narrow the filter).
 * examined: number of Packs examined from the collector (for truncation decisions/debugging).
 */
public record XlogSearchResult(List<XLogRowDto> rows, boolean truncated, boolean scanCapReached, int examined) {
}
