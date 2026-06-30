package scouter.mcp.scouter.dto;

import scouter.mcp.scouter.PackMapper;

import java.util.List;

public record CounterSeriesDto(int objHash, String counter, List<PackMapper.Point> points) {
}
