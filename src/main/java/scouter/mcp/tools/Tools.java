package scouter.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Tools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Tools() {
    }

    public static String renderListObjects(ScouterClient client, String objType, String nameLike) {
        List<SObjectDto> objects = client.listObjects().stream()
                .filter(o -> objType == null || objType.equalsIgnoreCase(o.objType()))
                .filter(o -> nameLike == null || o.objName().contains(nameLike))
                .collect(Collectors.toList());
        try {
            return MAPPER.writeValueAsString(Map.of("count", objects.size(), "objects", objects));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
