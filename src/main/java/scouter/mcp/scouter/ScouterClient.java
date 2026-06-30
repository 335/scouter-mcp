package scouter.mcp.scouter;

import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.scouter.dto.XlogSearchResult;

import java.util.List;

public interface ScouterClient extends AutoCloseable {

    void connect();                 // login + acquire session

    List<SObjectDto> listObjects(); // list of objects (agents)

    // Counter time series over a past range (multiple objHashes)
    List<CounterSeriesDto> getCounter(List<Integer> objHashes, String counter, long fromMillis, long toMillis);

    // Available counter metadata for an objType (name/displayName/unit)
    List<CounterMetaDto> listCounters(String objType);

    // Search XLogs (transactions). Stops early at the limit/scan cap during streaming and returns a truncation signal as well.
    XlogSearchResult searchXlog(SearchXlogParams params);

    // Single XLog detail (summary + profile steps/SQL/errors). Bind parameters are masked when maskSensitive=true.
    XLogDetailDto getXlogDetail(long txid, String yyyymmdd, boolean includeBindParams, boolean maskSensitive);

    // List of XLogs belonging to the same gxid (global transaction).
    List<XLogRowDto> getXlogByGxid(long gxid, String yyyymmdd);

    @Override
    void close();
}
