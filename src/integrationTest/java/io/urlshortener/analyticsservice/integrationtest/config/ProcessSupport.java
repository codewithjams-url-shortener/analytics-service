package io.urlshortener.analyticsservice.integrationtest.config;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Helpers for launching a built Spring Boot jar as a separate OS process and waiting for it to become healthy, shared by
 * {@link LocalEnvironmentConfig} and {@link LocalAnalyticsService}.
 *
 * <p>
 *     Each process's output goes to its own file under {@code build/integration-test-logs}, not the test output: three
 *     services logging at once would bury the test results, and the files are what to read when a test fails.
 * </p>
 */
final class ProcessSupport {

	private static final File LOG_DIRECTORY = new File("build/integration-test-logs");

	private ProcessSupport() {
	}

	/**
	 * @return the path of the {@code java} binary running this JVM, so the launched services use the same JDK.
	 */
	static String javaBinary() {
		return System.getProperty("java.home") + "/bin/java";
	}

	/**
	 * @return a currently free local port.
	 * @throws IOException if no port could be reserved.
	 */
	static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/**
	 * Starts a process, appending its combined output to {@code <name>.log} under {@code build/integration-test-logs}.
	 *
	 * @param name    the name used for the log file.
	 * @param command the full command line.
	 * @return the started process.
	 * @throws IOException if the process or its log file could not be created.
	 */
	static Process start(final String name, final List<String> command) throws IOException {
		if (!LOG_DIRECTORY.isDirectory() && !LOG_DIRECTORY.mkdirs()) {
			throw new IOException("Could not create %s".formatted(LOG_DIRECTORY));
		}
		final ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectErrorStream(true);
		processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(LOG_DIRECTORY, name + ".log")));
		return processBuilder.start();
	}

	/**
	 * Blocks until the process answers {@code /actuator/health}, failing early if the process exits first.
	 *
	 * @param name    the service name, for the failure message.
	 * @param port    the port the process listens on.
	 * @param process the launched process.
	 */
	static void waitUntilHealthy(final String name, final int port, final Process process) {
		final RestClient healthClient = RestClient.create();
		final Duration timeout = LocalTestSettings.healthCheckTimeout();
		final Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			if (!process.isAlive()) {
				throw new IllegalStateException(
						"%s exited before becoming healthy, exit code %s (see build/integration-test-logs/%s.log)"
								.formatted(name, process.exitValue(), name));
			}
			try {
				healthClient.get()
						.uri("http://localhost:" + port + "/actuator/health")
						.retrieve()
						.toBodilessEntity();
				return;
			} catch (RestClientException e) {
				sleep();
			}
		}
		throw new IllegalStateException(
				"%s did not become healthy within %s (see build/integration-test-logs/%s.log)"
						.formatted(name, timeout, name));
	}

	private static void sleep() {
		try {
			Thread.sleep(LocalTestSettings.healthCheckPollInterval().toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
