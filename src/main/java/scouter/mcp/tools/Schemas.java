package scouter.mcp.tools;

public final class Schemas {
    private Schemas() {
    }

    public static final String LIST_OBJECTS = """
        {
          "type": "object",
          "properties": {
            "objType": {"type": "string", "description": "필터: 오브젝트 타입(예: tomcat)"},
            "nameLike": {"type": "string", "description": "필터: objName 부분일치"}
          }
        }
        """;

    public static final String GET_COUNTER = """
        {
          "type": "object",
          "properties": {
            "objHashes": {"type": "array", "items": {"type": "integer"}, "description": "대상 오브젝트 해시 목록"},
            "objType": {"type": "string", "description": "objType 지정 시 해당 타입의 모든 오브젝트를 대상으로 함"},
            "counter": {"type": "string", "description": "카운터 이름(예: Cpu, Heap)"},
            "from": {"type": "string", "description": "시작 시각(예: now-1h, 2026-06-29T10:00)"},
            "to": {"type": "string", "description": "종료 시각(예: now)"}
          },
          "required": ["counter", "from", "to"]
        }
        """;

    public static final String LIST_COUNTERS = """
        {
          "type": "object",
          "properties": {
            "objType": {"type": "string", "description": "오브젝트 타입(예: tomcat)"}
          },
          "required": ["objType"]
        }
        """;

    public static final String SEARCH_XLOG = """
        {
          "type": "object",
          "properties": {
            "from": {"type": "string", "description": "시작 시각(예: now-1h, 2026-06-29T10:00)"},
            "to": {"type": "string", "description": "종료 시각(예: now)"},
            "objHash": {"type": "integer", "description": "필터: 특정 오브젝트 해시"},
            "service": {"type": "string", "description": "필터: 서비스명 패턴"},
            "minElapsedMs": {"type": "integer", "description": "필터: 최소 응답시간(ms)"},
            "onlyError": {"type": "boolean", "description": "필터: 에러 트랜잭션만"},
            "limit": {"type": "integer", "description": "최대 반환 건수(기본 100, 최대 1000)"}
          },
          "required": ["from", "to"]
        }
        """;

    public static final String GET_XLOG_DETAIL = """
        {
          "type": "object",
          "properties": {
            "txid": {"type": "string", "description": "트랜잭션 ID(64-bit long). 일부 MCP 클라이언트는 큰 숫자를 JSON number로 보내면 정밀도가 손실되므로 문자열로 전달"},
            "date": {"type": "string", "description": "조회 날짜 yyyyMMdd. 미지정 시 at 또는 오늘"},
            "at": {"type": "string", "description": "조회 시각(예: now-1h, 2026-06-29T10:00). date 미지정 시 날짜 산출에 사용"},
            "includeBindParams": {"type": "boolean", "description": "바인드 파라미터 포함 여부(기본 true)"},
            "maskSensitive": {"type": "boolean", "description": "민감정보 마스킹 여부(기본 true)"}
          },
          "required": ["txid"]
        }
        """;

    public static final String GET_XLOG_BY_GXID = """
        {
          "type": "object",
          "properties": {
            "gxid": {"type": "string", "description": "글로벌 트랜잭션 ID(64-bit long). 일부 MCP 클라이언트는 큰 숫자를 JSON number로 보내면 정밀도가 손실되므로 문자열로 전달"},
            "date": {"type": "string", "description": "조회 날짜 yyyyMMdd. 미지정 시 at 또는 오늘"},
            "at": {"type": "string", "description": "조회 시각(예: now-1h). date 미지정 시 날짜 산출에 사용"}
          },
          "required": ["gxid"]
        }
        """;
}
