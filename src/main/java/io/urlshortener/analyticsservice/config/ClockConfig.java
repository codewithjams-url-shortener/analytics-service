package io.urlshortener.analyticsservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the {@link Clock} that time-dependent code reads, so tests can substitute a fixed one.
 */
@Slf4j
@Configuration
public class ClockConfig {

	/**
	 * Builds the application-wide clock.
	 *
	 * @return a clock on the system time in UTC, so "today" is always the UTC day.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
