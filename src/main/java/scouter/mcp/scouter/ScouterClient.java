package scouter.mcp.scouter;

import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;

public interface ScouterClient extends AutoCloseable {

    void connect();                 // 로그인 + 세션 확보

    List<SObjectDto> listObjects(); // 오브젝트(에이전트) 목록

    @Override
    void close();
}
