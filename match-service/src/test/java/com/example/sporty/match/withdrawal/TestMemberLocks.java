package com.example.sporty.match.withdrawal;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

/** H2-only substitute; MariaDB tests use the production session lock. */
class TestMemberLocks extends MemberLocks {
    private static final ConcurrentMap<Long, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    TestMemberLocks(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public void lock(Long userId) {
        var mutex = LOCKS.computeIfAbsent(userId, id -> new ReentrantLock());
        try {
            if (!mutex.tryLock(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Member operation already in progress");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Member operation interrupted", ex);
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        mutex.unlock();
                    }
                });
    }
}
