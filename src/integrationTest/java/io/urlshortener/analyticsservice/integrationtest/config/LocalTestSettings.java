package io.urlshortener.analyticsservice.integrationtest.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

/**
 * Tunable constants for the {@code local} profile, read from {@code application.yaml} on the classpath
 * rather than hardcoded. Loaded standalone (not via {@code @Value}/{@code @ConfigurationProperties})
 * because {@link LocalEnvironmentConfig} needs these values inside a {@code static} initializer, which
 * runs before any Spring context exists.
 */
final class LocalTestSettings {

	private static final String PREFIX = "integration-test.local.";

	private static final Properties PROPERTIES = load();

	private LocalTestSettings() {
	}

	static String linksTableName() {
		return require("links-table-name");
	}

	static String linksTablePartitionKey() {
		return require("links-table-partition-key");
	}

	static String clickEventsTopicName() {
		return require("click-events-topic-name");
	}

	static String clickEventsQueueName() {
		return require("click-events-queue-name");
	}

	static String clickEventsQueueVisibilityTimeoutSeconds() {
		return require("click-events-queue-visibility-timeout-seconds");
	}

	static String bucketName() {
		return require("bucket-name");
	}

	static String postgresImage() {
		return require("postgres-image");
	}

	static String databaseName() {
		return require("database-name");
	}

	static String databaseUsername() {
		return require("database-username");
	}

	static String databasePassword() {
		return require("database-password");
	}

	static String redisImage() {
		return require("redis-image");
	}

	static Duration healthCheckTimeout() {
		return DurationStyle.detectAndParse(require("health-check-timeout"));
	}

	static Duration healthCheckPollInterval() {
		return DurationStyle.detectAndParse(require("health-check-poll-interval"));
	}

	private static String require(final String key) {
		final String fullKey = PREFIX + key;
		final String value = PROPERTIES.getProperty(fullKey);
		if (value == null) {
			throw new IllegalStateException("Missing required property '" + fullKey + "' in application.yaml");
		}
		return value;
	}

	private static Properties load() {
		final YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
		yamlFactory.setResources(new ClassPathResource("application.yaml"));
		final Properties properties = yamlFactory.getObject();
		if (properties == null) {
			throw new IllegalStateException("Failed to load application.yaml for integration tests");
		}
		return properties;
	}

}
