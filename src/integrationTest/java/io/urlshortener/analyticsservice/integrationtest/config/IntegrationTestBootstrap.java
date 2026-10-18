package io.urlshortener.analyticsservice.integrationtest.config;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;

/**
 * Purpose-built, minimal Spring Boot context for the integration tests in this source set. Deliberately not
 * {@code AnalyticsServiceApplication} itself: these tests treat {@code analytics-service}, {@code url-service} and
 * {@code redirect-service} as HTTP black boxes (separately launched processes for the {@code local} profile,
 * already-deployed instances for {@code deployed}), so this context exists only to host the {@code @Profile}-gated
 * client beans, never to run any application under test in-process.
 *
 * <p>
 *     The JDBC auto-configuration is excluded because only the {@code local} profile has a database the tests can reach
 *     ({@link LocalEnvironmentConfig} builds its own {@code JdbcTemplate}). Left on, it would try to build a
 *     {@code DataSource} from absent properties and fail the {@code deployed} profile at startup.
 * </p>
 */
@SpringBootApplication(exclude = {
		DataSourceAutoConfiguration.class,
		DataSourceTransactionManagerAutoConfiguration.class,
		JdbcTemplateAutoConfiguration.class
})
public class IntegrationTestBootstrap {
}
