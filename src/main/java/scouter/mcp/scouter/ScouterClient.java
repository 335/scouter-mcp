package scouter.mcp.scouter;

import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;

import java.util.List;

public interface ScouterClient extends AutoCloseable {

    void connect();                 // 로그인 + 세션 확보

    List<SObjectDto> listObjects(); // 오브젝트(에이전트) 목록

    // 과거 구간 카운터 시계열 (objHash 다중)
    List<CounterSeriesDto> getCounter(List<Integer> objHashes, String counter, long fromMillis, long toMillis);

    // objType의 사용 가능 카운터 메타(name/displayName/unit)
    List<CounterMetaDto> listCounters(String objType);

    // XLog(트랜잭션) 검색. 결과는 최대 params.limit() 건까지 반환한다(텍스트 해석 포함).
    List<XLogRowDto> searchXlog(SearchXlogParams params);

    // 단일 XLog 상세(요약 + 프로파일 스텝/SQL/에러). 바인드 파라미터는 maskSensitive=true 시 마스킹된다.
    XLogDetailDto getXlogDetail(long txid, String yyyymmdd, boolean includeBindParams, boolean maskSensitive);

    // 동일 gxid(글로벌 트랜잭션)에 속한 XLog 목록.
    List<XLogRowDto> getXlogByGxid(long gxid, String yyyymmdd);

    @Override
    void close();
}
