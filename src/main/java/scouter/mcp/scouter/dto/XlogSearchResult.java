package scouter.mcp.scouter.dto;

import java.util.List;

/**
 * search_xlog result + truncation signal.
 * truncated: more results may exist because the limit or scan cap was hit.
 * scanCapReached: stopped because the scan cap (Limits.SEARCH_SCAN_CAP) was reached (a signal to narrow the filter).
 * examined: number of Packs examined from the collector (for truncation decisions/debugging).
 * serviceLooksLikeApp: rows are empty AND the service filter token matches an application objName —
 * the caller almost certainly confused an app name with a service URL and should use objNameLike.
 * serviceCandidates: rows are empty AND real service names similar to the query were discovered in
 * the same window (case-insensitive token match) — retry with one of these exact names.
 */
public record XlogSearchResult(List<XLogRowDto> rows, boolean truncated, boolean scanCapReached, int examined,
                               boolean serviceLooksLikeApp, List<String> serviceCandidates) {
}
