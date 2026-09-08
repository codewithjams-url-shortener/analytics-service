package io.urlshortener.analyticsservice;

import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlociContainerIntegrationTest {

	@Container
	static final FlociContainer floci = new FlociContainer();

	@Test
	void isolatedFlociInstanceIsReachable() {

		// Arrange
		final URI endpoint = URI.create(floci.getEndpoint());
		final Region region = Region.of(floci.getRegion());
		final AwsCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
				AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())
		);
		final DynamoDbClientBuilder dynamoDbBuilder = DynamoDbClient.builder()
				.endpointOverride(endpoint)
				.region(region)
				.credentialsProvider(credentialsProvider);

		try (final DynamoDbClient client = dynamoDbBuilder.build()) {

			// Act
			final List<String> tables = client.listTables().tableNames();

			// Assert
			assertThat(tables).isEmpty();

		}

	}

}
