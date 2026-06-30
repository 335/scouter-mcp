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
}
