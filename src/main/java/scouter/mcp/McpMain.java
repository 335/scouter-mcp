package scouter.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import scouter.mcp.config.Config;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.TcpScouterClient;
import scouter.mcp.tools.Schemas;
import scouter.mcp.tools.Tools;

import java.util.Map;

public final class McpMain {

    private McpMain() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnv(System.getenv());
        ScouterClient client = new TcpScouterClient(config);
        client.connect();

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        McpSchema.Tool listObjects = McpSchema.Tool.builder()
                .name("list_objects")
                .description("Scouter 수집 대상 오브젝트(에이전트) 목록을 조회한다. objType/nameLike로 필터링 가능")
                .inputSchema(jsonMapper, Schemas.LIST_OBJECTS)
                .build();

        McpServerFeatures.SyncToolSpecification listObjectsSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listObjects)
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments();
                    String objType = asString(arguments, "objType");
                    String nameLike = asString(arguments, "nameLike");
                    String json = Tools.renderListObjects(client, objType, nameLike);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(json)
                            .build();
                })
                .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("scouter-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(listObjectsSpec)
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } finally {
                client.close();
            }
        }));
    }

    private static String asString(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
