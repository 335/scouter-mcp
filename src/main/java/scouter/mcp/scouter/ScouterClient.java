package scouter.mcp.scouter;

import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;

public interface ScouterClient extends AutoCloseable {

    void connect();                 // 로그인 + 세션 확보

    List<SObjectDto> listObjects(); // 오브젝트(에이전트) 목록

    // 과거 구간 카운터 시계열 (objHash 다중)
    List<CounterSeriesDto> getCounter(List<Integer> objHashes, String counter, long fromMillis, long toMillis);

    // objType의 사용 가능 카운터 메타(name/displayName/unit)
    List<CounterMetaDto> listCounters(String objType);

    @Override
    void close();
}
