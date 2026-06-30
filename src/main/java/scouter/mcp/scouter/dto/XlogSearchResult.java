package scouter.mcp.scouter.dto;

import java.util.List;

/**
 * search_xlog 결과 + 절단 신호.
 * truncated: limit 또는 스캔 상한에 걸려 더 많은 결과가 있을 수 있음.
 * scanCapReached: 스캔 상한(Limits.SEARCH_SCAN_CAP) 도달로 중단됨(필터를 좁히라는 신호).
 * examined: 컬렉터로부터 검사한 Pack 수(절단 판단/디버깅용).
 */
public record XlogSearchResult(List<XLogRowDto> rows, boolean truncated, boolean scanCapReached, int examined) {
}
