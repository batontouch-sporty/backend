package com.example.sporty.match.withdrawal;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class WithdrawalHealthConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DataSourceHealthContributorAutoConfiguration.class))
        .withBean(DataSource.class,()->mock(DataSource.class))
        .withInitializer(context->{try{
            var sources=new YamlPropertySourceLoader().load("actual-match-yaml",new ClassPathResource("application.yml"));
            sources.forEach(source->context.getEnvironment().getPropertySources().addLast(source));
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}});
    @Test void disabledSagaDoesNotMakeDbHealthMandatory(){runner.withPropertyValues("MATCH_WITHDRAWAL_ENABLED=false").run(context->assertThat(context).doesNotHaveBean("dbHealthContributor"));}
    @Test void enabledSagaIncludesDbHealth(){runner.withPropertyValues("MATCH_WITHDRAWAL_ENABLED=true").run(context->assertThat(context).hasBean("dbHealthContributor"));}
}
