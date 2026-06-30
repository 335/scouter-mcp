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
}
