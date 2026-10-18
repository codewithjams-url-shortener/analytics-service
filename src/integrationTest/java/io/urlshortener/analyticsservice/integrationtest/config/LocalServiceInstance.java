package io.urlshortener.analyticsservice.integrationtest.config;

import java.util.concurrent.TimeUnit;

/**
 * A service instance launched as a genuine separate OS process by {@link LocalEnvironmentConfig}, together with the
 * port it is listening on. Used for {@code url-service} and {@code redirect-service}, which are needed only to generate
 * the links and clicks {@code analytics-service} consumes. {@code analytics-service} itself is a
 * {@link LocalAnalyticsService}, because some tests must stop and restart it.
 */
public record LocalServiceInstance(Process process, int port) {

	/**
	 * Stops the process, escalating to a forceful kill if it does not exit promptly.
	 */
	public void shutdown() {
		process.destroy();
		try {
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

}
