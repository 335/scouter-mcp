package scouter.mcp.scouter;

import scouter.mcp.scouter.dto.ActiveServiceDto;
import scouter.mcp.scouter.dto.AlertDto;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.scouter.dto.XlogSearchResult;
import scouter.mcp.scouter.dto.XlogSummaryResult;

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

    // Aggregate XLogs per service over a window (count/avg/max/p95/errorRate) without retaining raw rows.
    XlogSummaryResult getServiceSummary(SearchXlogParams params);

    // Single XLog detail (summary + profile steps/SQL/errors).
    XLogDetailDto getXlogDetail(long txid, String yyyymmdd, boolean includeBindParams);

    // List of XLogs belonging to the same gxid (global transaction).
    List<XLogRowDto> getXlogByGxid(long gxid, String yyyymmdd);

    // Past alerts over a window (ALERT_LOAD_TIME). level/object/key are optional server-side filters.
    List<AlertDto> getAlerts(long fromMillis, long toMillis, String level, String object, String key, int limit);

    // Currently-running services on an agent/objType (OBJECT_ACTIVE_SERVICE_LIST). Real-time snapshot.
    List<ActiveServiceDto> getActiveServices(String objType, Long objHash);

    @Override
    void close();
}
