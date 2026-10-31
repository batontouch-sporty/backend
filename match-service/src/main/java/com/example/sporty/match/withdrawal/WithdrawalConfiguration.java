package com.example.sporty.match.withdrawal;
import java.time.Clock;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="match.withdrawal.enabled",havingValue="true")
public class WithdrawalConfiguration {
    @Bean WithdrawalStore withdrawalStore(JdbcTemplate jdbc,ObjectMapper mapper){return new WithdrawalStore(jdbc,mapper);}
    @Bean MemberLocks withdrawalMemberLocks(DataSource ds){return new MemberLocks(ds);}
    @Bean WithdrawalProcessor withdrawalProcessor(WithdrawalStore store,MemberLocks locks,PlatformTransactionManager manager){return new WithdrawalProcessor(store,locks,manager,Clock.systemUTC());}
    @Bean WithdrawalListener withdrawalListener(ObjectMapper mapper,WithdrawalProcessor processor){return new WithdrawalListener(mapper,processor);}
}
