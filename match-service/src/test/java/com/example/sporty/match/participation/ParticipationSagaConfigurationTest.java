package com.example.sporty.match.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.example.sporty.match.withdrawal.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class ParticipationSagaConfigurationTest {
    @Test void enablingSagaKeepsBothLockBeansUnambiguous() {
        new ApplicationContextRunner()
            .withUserConfiguration(ParticipationConfiguration.class,WithdrawalConfiguration.class)
            .withPropertyValues("match.withdrawal.enabled=true")
            .withBean(DataSource.class,()->mock(DataSource.class))
            .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
            .run(context->{
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(MemberLocks.class)).hasSize(2);
                assertThat(ReflectionTestUtils.getField(context.getBean(WithdrawalProcessor.class),"locks"))
                    .isSameAs(context.getBean("withdrawalMemberLocks"));
            });
    }
}
