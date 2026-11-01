package com.example.sporty.match.withdrawal;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Serializes member cleanup even when the member has no match rows to lock. A processed_events PK
 * alone cannot serialize different event IDs for the same Saga.
 */
public class MemberLocks {
    private final DataSource dataSource;

    public MemberLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Hold a MariaDB session lock through transaction completion, across replicas. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long userId) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        String name = "sporty.match.member." + userId;
        try {
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 3)")) {
                statement.setString(1, name);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("회원 잠금 결과가 없습니다.");
                    int acquired = result.getInt(1);
                    if (result.wasNull()) throw new IllegalStateException("회원 잠금 획득에 실패했습니다.");
                    if (acquired == 0) throw new MemberLockTimeoutException();
                    if (acquired != 1) throw new IllegalStateException("회원 잠금 결과가 올바르지 않습니다.");
                }
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            try (var statement =
                                    connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                                statement.setString(1, name);
                                statement.execute();
                            } catch (SQLException ex) {
                                throw new IllegalStateException(
                                        "Could not release member advisory lock", ex);
                            }
                        }
                    });
        } catch (SQLException ex) {
            throw new IllegalStateException("회원 작업 잠금 실패", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
