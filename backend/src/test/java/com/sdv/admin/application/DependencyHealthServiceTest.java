package com.sdv.admin.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * F-BE-015 검증: 실제로 확인 가능한 DB만 UP/DOWN을 보고하고, 나머지 의존성은
 * 절대 UP으로 조작되지 않는다(INS-003, OPS-006).
 */
@ExtendWith(MockitoExtension.class)
class DependencyHealthServiceTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Test
    void reportsDatabaseUpWhenConnectionIsValid() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        Map<String, String> result = new DependencyHealthService(dataSource).checkAll();

        assertThat(result.get("database")).isEqualTo("UP");
    }

    @Test
    void reportsDatabaseDownWhenConnectionFails() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("simulated failure"));

        Map<String, String> result = new DependencyHealthService(dataSource).checkAll();

        assertThat(result.get("database")).isEqualTo("DOWN");
    }

    @Test
    void neverReportsUnimplementedDependenciesAsUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        Map<String, String> result = new DependencyHealthService(dataSource).checkAll();

        assertThat(result.get("kafka")).isEqualTo("NOT_IMPLEMENTED");
        assertThat(result.get("keycloak")).isEqualTo("NOT_IMPLEMENTED");
        assertThat(result.get("aiService")).isEqualTo("NOT_IMPLEMENTED");
        assertThat(result.get("source")).isEqualTo("NOT_IMPLEMENTED");

        long upCount = result.values().stream().filter("UP"::equals).count();
        assertThat(upCount).isEqualTo(1);
    }
}
