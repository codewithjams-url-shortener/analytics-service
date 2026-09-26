package io.urlshortener.analyticsservice.config;

import io.urlshortener.analyticsservice.property.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.util.Objects;

/**
 * Provides the AWS SDK beans (region, credentials, the SQS client, and the S3 client) used throughout
 * analytics-service, configured from {@link AwsProperties}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AwsConfig {

	/**
	 * Source of the {@code aws.*} configuration values used to build the beans below.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Builds the AWS region bean from configuration.
	 *
	 * @return the AWS {@link Region} configured via {@code aws.region}.
	 */
	@Bean
	public Region region() {
		final String region = awsProperties.getRegion();
		log.atDebug()
				.addKeyValue("bean", Region.class.getSimpleName())
				.addKeyValue("region.present", StringUtils.hasText(region))
				.log("Bean: Region created");
		return Region.of(region);
	}

	/**
	 * Builds the static credentials bean from configuration.
	 *
	 * @return static {@link AwsCredentials} built from the configured access/secret key pair.
	 */
	@Bean
	public AwsCredentials awsCredentials() {
		final String accessKey = awsProperties.getCredential().getAccessKey();
		final String secretKey = awsProperties.getCredential().getSecretKey();
		log.atDebug()
				.addKeyValue("bean", AwsCredentials.class.getSimpleName())
				.addKeyValue("accessKey.present", StringUtils.hasText(accessKey))
				.addKeyValue("secretKey.present", StringUtils.hasText(secretKey))
				.log("Bean: AwsCredentials created");
		return AwsBasicCredentials.create(accessKey, secretKey);
	}

	/**
	 * Wraps the static credentials bean in a provider, as required by the AWS SDK client builders.
	 *
	 * @param credentials the AWS credentials to wrap.
	 * @return an {@link AwsCredentialsProvider} that always returns {@code credentials}.
	 */
	@Bean
	public AwsCredentialsProvider credentialsProvider(final AwsCredentials credentials) {
		log.atDebug()
				.addKeyValue("bean", AwsCredentialsProvider.class.getSimpleName())
				.addKeyValue("credentials.present", Objects.nonNull(credentials))
				.log("Bean: AwsCredentialsProvider created");
		return StaticCredentialsProvider.create(credentials);
	}

	/**
	 * Builds the SQS client used to poll for {@code ClickEvent}s.
	 *
	 * @param region              the AWS region to target.
	 * @param credentialsProvider the credentials to authenticate with.
	 * @return an {@link SqsClient}, pointed at the configured endpoint override when present (e.g. for local
	 * development against floci).
	 */
	@Bean
	public SqsClient sqsClient(final Region region, final AwsCredentialsProvider credentialsProvider) {
		final String url = awsProperties.getSqs().getEndpointOverride();
		final URI endpoint = URI.create(url);
		log.atDebug()
				.addKeyValue("bean", SqsClient.class.getSimpleName())
				.addKeyValue("region.present", Objects.nonNull(region))
				.addKeyValue("credentialsProvider.present", Objects.nonNull(credentialsProvider))
				.addKeyValue("endpoint-override.present", StringUtils.hasText(url))
				.log("Bean: SqsClient created");
		return SqsClient.builder()
				.region(region)
				.endpointOverride(endpoint)
				.credentialsProvider(credentialsProvider)
				.build();
	}

	/**
	 * Builds the S3 client used to archive raw click events.
	 *
	 * @param region              the AWS region to target.
	 * @param credentialsProvider the credentials to authenticate with.
	 * @return an {@link S3Client}, pointed at the configured endpoint override when present (e.g. for local development
	 * against floci) with path-style addressing forced on, since a local endpoint typically can't resolve
	 * virtual-hosted-style bucket subdomains.
	 */
	@Bean
	public S3Client s3Client(final Region region, final AwsCredentialsProvider credentialsProvider) {
		final String url = awsProperties.getS3().getEndpointOverride();
		final URI endpoint = URI.create(url);
		log.atDebug()
				.addKeyValue("bean", S3Client.class.getSimpleName())
				.addKeyValue("region.present", Objects.nonNull(region))
				.addKeyValue("credentialsProvider.present", Objects.nonNull(credentialsProvider))
				.addKeyValue("endpoint-override.present", StringUtils.hasText(url))
				.log("Bean: S3Client created");
		return S3Client.builder()
				.region(region)
				.endpointOverride(endpoint)
				.credentialsProvider(credentialsProvider)
				.forcePathStyle(true)
				.build();
	}

}
