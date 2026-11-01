package com.example.sporty.match.withdrawal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;

import javax.sql.DataSource;

class MemberLocksTest {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    MemberLocks locks = new MemberLocks(dataSource);

    @BeforeEach
    void setup() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
    }

    @Test
    void onlyZeroIsReportedAsMemberLockTimeout() throws Exception {
        when(result.getInt(1)).thenReturn(0);
        assertThatThrownBy(() -> locks.lock(2L)).isInstanceOf(MemberLockTimeoutException.class);
    }

    @Test
    void sqlNullIsNotReportedAsContention() throws Exception {
        when(result.wasNull()).thenReturn(true);
        assertThatThrownBy(() -> locks.lock(2L))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(MemberLockTimeoutException.class);
    }

    @Test
    void databaseFailureKeepsItsCause() throws Exception {
        SQLException failure = new SQLException("database unavailable");
        when(statement.executeQuery()).thenThrow(failure);
        assertThatThrownBy(() -> locks.lock(2L))
                .isNotInstanceOf(MemberLockTimeoutException.class)
                .hasCause(failure);
    }
}
