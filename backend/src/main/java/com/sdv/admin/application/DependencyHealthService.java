package com.sdv.admin.application;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F-BE-015. DB/Kafka/Keycloak/AI/Source 의존성 상태 집계(INS-003, OPS-006).
 *
 * <p>현재 코드베이스가 실제로 검증할 수 있는 의존성(DataSource)만 진짜로 확인한다.
 * Kafka/Keycloak/AI Service/Source Connector는 아직 이 Backend에 연결되어 있지
 * 않으므로(각각 이후 Phase) {@code NOT_IMPLEMENTED}로 정직하게 보고한다 - 확인하지
 * 않은 의존성을 {@code UP}으로 조작하지 않는다.</p>
 */
@Service
public class DependencyHealthService {

    static final String UP = "UP";
    static final String DOWN = "DOWN";
    static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    private final DataSource dataSource;

    public DependencyHealthService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, String> checkAll() {
        Map<String, String> results = new LinkedHashMap<>();
        results.put("database", checkDatabase());
        results.put("kafka", NOT_IMPLEMENTED);
        results.put("keycloak", NOT_IMPLEMENTED);
        results.put("aiService", NOT_IMPLEMENTED);
        results.put("source", NOT_IMPLEMENTED);
        return Map.copyOf(results);
    }

    private String checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? UP : DOWN;
        } catch (SQLException e) {
            // 내부 예외 메시지(연결 문자열/자격증명 단서 포함 가능)를 그대로 노출하지 않는다.
            return DOWN;
        }
    }
}
