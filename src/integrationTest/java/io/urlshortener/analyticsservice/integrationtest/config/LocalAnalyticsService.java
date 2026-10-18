package io.urlshortener.analyticsservice.integrationtest.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The {@code analytics-service} under test, launched as a separate OS process and, unlike
 * {@link LocalServiceInstance}, restartable on the same port with the same configuration. The restart is what lets a
 * test prove the stream consumer resumes from its checkpoint instead of replaying.
 */
public class LocalAnalyticsService {

	private static final String NAME = "analytics-service";

	private final List<String> command;

	private final int port;

	private Process process;

	/**
	 * Creates the service without starting it.
	 *
	 * @param command the full command line, including the {@code -Dserver.port} for {@code port}.
	 * @param port    the port the process will listen on.
	 */
	LocalAnalyticsService(final List<String> command, final int port) {
		this.command = command;
		this.port = port;
	}

	/**
	 * @return the port the service listens on.
	 */
	public int port() {
		return port;
	}

	/**
	 * Starts the process and waits until it is healthy. A no-op if it is already running.
	 */
	public synchronized void start() {
		if (isRunning()) {
			return;
		}
		try {
			process = ProcessSupport.start(NAME, command);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to start %s".formatted(NAME), e);
		}
		ProcessSupport.waitUntilHealthy(NAME, port, process);
	}

	/**
	 * Stops the process, escalating to a forceful kill if it does not exit promptly. A no-op if it is not running.
	 */
	public synchronized void stop() {
		if (Objects.isNull(process) || !process.isAlive()) {
			return;
		}
		process.destroy();
		try {
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(10, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	/**
	 * @return {@code true} if the process has been started and has not exited.
	 */
	public synchronized boolean isRunning() {
		return Objects.nonNull(process) && process.isAlive();
	}

}
