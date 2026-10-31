package com.example.sporty.match.withdrawal;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;



public class MemberLocks {
    private final DataSource dataSource;
    public MemberLocks(DataSource dataSource){this.dataSource=dataSource;}
    private static final ConcurrentMap<Long,ReentrantLock> TEST_LOCKS=new ConcurrentHashMap<>();
    @Transactional(propagation=Propagation.MANDATORY)
    public void lock(Long userId){
        Connection connection=DataSourceUtils.getConnection(dataSource);
        try {
            if(connection.getMetaData().getDatabaseProductName().equals("H2")) {
                // H2-only test substitute; production uses database-owned session locks, not an in-process mutex.
                var mutex=TEST_LOCKS.computeIfAbsent(userId,id->new ReentrantLock());
                if(!mutex.tryLock(3,TimeUnit.SECONDS))throw new IllegalStateException("회원 작업 처리 중");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){mutex.unlock();}});
            } else {
                String name="sporty.match.member."+userId;
                try(var statement=connection.prepareStatement("SELECT GET_LOCK(?, 3)")){
                    statement.setString(1,name);
                    try(var result=statement.executeQuery()){
                        if(!result.next() || result.getInt(1)!=1)throw new IllegalStateException("회원 작업 처리 중");
                    }
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    public void afterCompletion(int status){
                        try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,name);statement.execute();}
                        catch(SQLException ex){throw new IllegalStateException("Could not release member advisory lock",ex);}
                    }
                });
            }
        }catch(SQLException ex){throw new IllegalStateException("회원 작업 잠금 실패");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("회원 작업 중단");}
        finally{DataSourceUtils.releaseConnection(connection,dataSource);}
    }
}
