package io.urlshortener.analyticsservice.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code aws.*} configuration properties used to construct the AWS SDK beans in
 * {@link io.urlshortener.analyticsservice.config.AwsConfig AwsConfig}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "aws")
public class AwsProperties {

	/**
	 * AWS region to target, e.g. {@code us-east-1}.
	 */
	private String region;

	/**
	 * Static credentials to authenticate with.
	 */
	private Credential credential;

	/**
	 * SQS-specific configuration.
	 */
	private Sqs sqs;

	/**
	 * S3-specific configuration.
	 */
	private S3 s3;

	/**
	 * Static AWS credentials, configured directly rather than resolved via the default provider chain.
	 */
	@Getter
	@Setter
	public static class Credential {

		/**
		 * AWS access key ID.
		 */
		private String accessKey;

		/**
		 * AWS secret access key.
		 */
		private String secretKey;

	}

	/**
	 * SQS-specific configuration: an optional local endpoint override, and the URL of the {@code click-events} queue
	 * {@link io.urlshortener.analyticsservice.consumer.ClickEventConsumer ClickEventConsumer} polls.
	 */
	@Getter
	@Setter
	public static class Sqs {

		/**
		 * Local SQS endpoint to target instead of the real AWS endpoint, if set (e.g. floci).
		 */
		private String endpointOverride;

		/**
		 * URL of the SQS queue to poll for {@code ClickEvent}s.
		 */
		private String queueUrl;

	}

	/**
	 * S3-specific configuration: an optional local endpoint override, and the bucket raw click events are archived to.
	 */
	@Getter
	@Setter
	public static class S3 {

		/**
		 * Local S3 endpoint to target instead of the real AWS endpoint, if set (e.g. floci).
		 */
		private String endpointOverride;

		/**
		 * Name of the S3 bucket raw click events are archived to.
		 */
		private String bucketName;

	}

}
