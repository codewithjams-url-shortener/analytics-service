package io.urlshortener.analyticsservice.integrationtest.config;

import io.floci.testcontainers.FlociContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

/**
 * Wires the shared integration tests to a real {@code analytics-service}, {@code url-service} and
 * {@code redirect-service}, each launched as a genuine separate OS process (the built boot jars, not in-process
 * {@code @SpringBootTest} contexts) against Testcontainers-managed floci (DynamoDB, SNS, SQS, S3), PostgreSQL, Redis and Jaeger.
 *
 * <p>
 *     {@code url-service} and {@code redirect-service} are started only to generate the traffic
 *     {@code analytics-service} consumes: links are created and deleted through url-service's real {@code /links} API,
 *     and clicked through redirect-service's real redirect, so the click events travel the real
 *     redirect-service to SNS to SQS to analytics-service path.
 * </p>
 *
 * <p>
 *     The click-events queue is subscribed to the topic <em>without</em> raw message delivery, exactly like the real
 *     {@code sns-sqs} Terraform module, so analytics-service parses the genuine SNS envelope. Its visibility timeout is
 *     short so a batch that failed and was left on the queue is redelivered within seconds.
 * </p>
 *
 * <p>
 *     Also exposes what only the {@code local} profile can reach: a {@link JdbcTemplate} onto the Postgres container
 *     and an {@link S3Client}, for assertions and for fault injection, and the {@link LocalAnalyticsService} so a test
 *     can restart analytics-service.
 * </p>
 *
 * <p>
 *     Active only under the {@code local} profile<br/>
 *     {@link DeployedEnvironmentConfig} supplies the HTTP clients for the {@code deployed} profile.
 * </p>
 */
@Configuration
@Profile("local")
public class LocalEnvironmentConfig {

	private static final int JAEGER_OTLP_HTTP_PORT = 4318;

	private static final int JAEGER_QUERY_PORT = 16686;

	private static final FlociContainer floci = new FlociContainer();

	private static final PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>(DockerImageName.parse(LocalTestSettings.postgresImage()))
					.withDatabaseName(LocalTestSettings.databaseName())
					.withUsername(LocalTestSettings.databaseUsername())
					.withPassword(LocalTestSettings.databasePassword());

	private static final GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse(LocalTestSettings.redisImage()))
					.withExposedPorts(6379)
					.waitingFor(Wait.forListeningPort());

	private static final GenericContainer<?> jaeger =
			new GenericContainer<>(DockerImageName.parse(LocalTestSettings.jaegerImage()))
					.withExposedPorts(JAEGER_OTLP_HTTP_PORT, JAEGER_QUERY_PORT)
					.waitingFor(Wait.forHttp("/").forPort(JAEGER_QUERY_PORT));

	private static final String clickEventsTopicArn;

	private static final String clickEventsQueueUrl;

	static {
		floci.start();
		postgres.start();
		redis.start();
		jaeger.start();
		createLinksTable();
		createBucket();
		clickEventsTopicArn = createClickEventsTopic();
		clickEventsQueueUrl = createClickEventsQueueSubscribedToTopic(clickEventsTopicArn);
	}

	private static void createLinksTable() {
		try (DynamoDbClient client = buildDynamoDbClient()) {
			final KeySchemaElement schemaElement = KeySchemaElement.builder()
					.attributeName(LocalTestSettings.linksTablePartitionKey())
					.keyType(KeyType.HASH)
					.build();
			final AttributeDefinition attributeDefinition = AttributeDefinition.builder()
					.attributeName(LocalTestSettings.linksTablePartitionKey())
					.attributeType(ScalarAttributeType.S)
					.build();
			client.createTable(CreateTableRequest.builder()
					.tableName(LocalTestSettings.linksTableName())
					.keySchema(schemaElement)
					.attributeDefinitions(attributeDefinition)
					.billingMode(BillingMode.PAY_PER_REQUEST)
					// NEW_AND_OLD_IMAGES to match production (see ADR-0003/ADR-0012) - analytics-service's
					// LinksStreamConsumer needs both images to classify a change.
					.streamSpecification(StreamSpecification.builder()
							.streamEnabled(true)
							.streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
							.build())
					.build());
		}
	}

	private static void createBucket() {
		try (S3Client client = buildS3Client()) {
			client.createBucket(CreateBucketRequest.builder().bucket(LocalTestSettings.bucketName()).build());
		}
	}

	private static String createClickEventsTopic() {
		try (SnsClient client = buildSnsClient()) {
			return client.createTopic(CreateTopicRequest.builder()
							.name(LocalTestSettings.clickEventsTopicName())
							.build())
					.topicArn();
		}
	}

	private static String createClickEventsQueueSubscribedToTopic(final String topicArn) {
		try (SqsClient sqsClient = buildSqsClient(); SnsClient snsClient = buildSnsClient()) {
			final String queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
							.queueName(LocalTestSettings.clickEventsQueueName())
							.attributes(Map.of(
									QueueAttributeName.VISIBILITY_TIMEOUT,
									LocalTestSettings.clickEventsQueueVisibilityTimeoutSeconds()
							))
							.build())
					.queueUrl();
			final String queueArn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
							.queueUrl(queueUrl)
							.attributeNames(QueueAttributeName.QUEUE_ARN)
							.build())
					.attributes()
					.get(QueueAttributeName.QUEUE_ARN);
			// Deliberately no RawMessageDelivery: the real sns-sqs module does not set it, so the queue body is the SNS
			// envelope with the ClickEvent JSON in its "Message" field - which is what analytics-service parses.
			snsClient.subscribe(SubscribeRequest.builder()
					.topicArn(topicArn)
					.protocol("sqs")
					.endpoint(queueArn)
					.build());
			return queueUrl;
		}
	}

	private static DynamoDbClient buildDynamoDbClient() {
		return DynamoDbClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.build();
	}

	private static SnsClient buildSnsClient() {
		return SnsClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.build();
	}

	private static SqsClient buildSqsClient() {
		return SqsClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.build();
	}

	private static S3Client buildS3Client() {
		return S3Client.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.forcePathStyle(true)
				.build();
	}

	private static StaticCredentialsProvider credentialsProvider() {
		return StaticCredentialsProvider.create(
				AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey()));
	}

	/**
	 * Launches analytics-service, the system under test.
	 *
	 * @param appJarPath the path of the built analytics-service boot jar.
	 * @return the started, healthy service.
	 * @throws IOException if no free port could be reserved.
	 */
	@Bean(destroyMethod = "stop")
	public LocalAnalyticsService analyticsService(
			@Value("${integration-test.app-jar}") final String appJarPath
	) throws IOException {
		final int port = ProcessSupport.findFreePort();
		final List<String> command = List.of(
				ProcessSupport.javaBinary(),
				"-Dserver.port=%s".formatted(port),
				"-Dspring.datasource.url=%s".formatted(postgres.getJdbcUrl()),
				"-Dspring.datasource.username=%s".formatted(postgres.getUsername()),
				"-Dspring.datasource.password=%s".formatted(postgres.getPassword()),
				"-Daws.region=%s".formatted(floci.getRegion()),
				"-Daws.credential.access-key=%s".formatted(floci.getAccessKey()),
				"-Daws.credential.secret-key=%s".formatted(floci.getSecretKey()),
				"-Daws.sqs.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.sqs.queue-url=%s".formatted(clickEventsQueueUrl),
				"-Daws.s3.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.s3.bucket-name=%s".formatted(LocalTestSettings.bucketName()),
				"-Daws.dynamodb.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.dynamodb.tables.links-table=%s".formatted(LocalTestSettings.linksTableName()),
				"-Daws.cloudwatch.endpoint-override=%s".formatted(floci.getEndpoint()),
				// Publish every second instead of every minute, so the tests need not wait a minute to see the counts.
				"-Durl-shortener.http-metrics.publish-interval=PT1S",
				"-Dmanagement.otlp.tracing.endpoint=%s".formatted(jaegerOtlpEndpoint()),
				// The per-event "acknowledged" line is debug-level; the tracing test reads it to prove consumer logs carry
				// the trace's traceId.
				"-Dlogging.level.io.urlshortener.analyticsservice.tracing=DEBUG",
				"-jar", appJarPath
		);
		final LocalAnalyticsService service = new LocalAnalyticsService(command, port);
		service.start();
		return service;
	}

	/**
	 * Launches url-service, used to create and delete the links analytics-service observes.
	 *
	 * @param appJarPath the path of the built url-service boot jar.
	 * @return the started, healthy instance.
	 * @throws IOException if no free port could be reserved.
	 */
	@Bean(destroyMethod = "shutdown")
	@Qualifier("urlServiceInstance")
	public LocalServiceInstance urlServiceInstance(
			@Value("${integration-test.url-service-app-jar}") final String appJarPath
	) throws IOException {
		final int port = ProcessSupport.findFreePort();
		final Process process = ProcessSupport.start("url-service", List.of(
				ProcessSupport.javaBinary(),
				"-Dserver.port=%s".formatted(port),
				"-Daws.dynamodb.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.region=%s".formatted(floci.getRegion()),
				"-Daws.credential.access-key=%s".formatted(floci.getAccessKey()),
				"-Daws.credential.secret-key=%s".formatted(floci.getSecretKey()),
				"-Daws.dynamodb.tables.links-table=%s".formatted(LocalTestSettings.linksTableName()),
				"-Dmanagement.otlp.tracing.endpoint=%s".formatted(jaegerOtlpEndpoint()),
				"-jar", appJarPath
		));
		ProcessSupport.waitUntilHealthy("url-service", port, process);
		return new LocalServiceInstance(process, port);
	}

	/**
	 * Launches redirect-service, used to click the links so click events are published.
	 *
	 * @param appJarPath the path of the built redirect-service boot jar.
	 * @return the started, healthy instance.
	 * @throws IOException if no free port could be reserved.
	 */
	@Bean(destroyMethod = "shutdown")
	@Qualifier("redirectServiceInstance")
	public LocalServiceInstance redirectServiceInstance(
			@Value("${integration-test.redirect-service-app-jar}") final String appJarPath
	) throws IOException {
		final int port = ProcessSupport.findFreePort();
		final Process process = ProcessSupport.start("redirect-service", List.of(
				ProcessSupport.javaBinary(),
				"-Dserver.port=%s".formatted(port),
				"-Daws.dynamodb.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.sns.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.region=%s".formatted(floci.getRegion()),
				"-Daws.credential.access-key=%s".formatted(floci.getAccessKey()),
				"-Daws.credential.secret-key=%s".formatted(floci.getSecretKey()),
				"-Daws.dynamodb.tables.links-table=%s".formatted(LocalTestSettings.linksTableName()),
				"-Daws.sns.topics.click-events-topic=%s".formatted(clickEventsTopicArn),
				"-Dspring.data.redis.host=%s".formatted(redis.getHost()),
				"-Dspring.data.redis.port=%s".formatted(redis.getMappedPort(6379)),
				"-Dmanagement.otlp.tracing.endpoint=%s".formatted(jaegerOtlpEndpoint()),
				"-jar", appJarPath
		));
		ProcessSupport.waitUntilHealthy("redirect-service", port, process);
		return new LocalServiceInstance(process, port);
	}

	/**
	 * @param service the analytics-service under test.
	 * @return a client for it. A 4xx/5xx response is returned, not thrown, so tests can assert on error statuses.
	 */
	@Bean
	public RestClient analyticsApiClient(final LocalAnalyticsService service) {
		return statusPreservingClient("http://localhost:" + service.port(), HttpClient.Redirect.NORMAL);
	}

	/**
	 * @param instance the url-service instance.
	 * @return a client for it. A 4xx/5xx response is returned, not thrown, so tests can assert on error statuses.
	 */
	@Bean
	public RestClient linksApiClient(@Qualifier("urlServiceInstance") final LocalServiceInstance instance) {
		return statusPreservingClient("http://localhost:" + instance.port(), HttpClient.Redirect.NORMAL);
	}

	/**
	 * @param instance the redirect-service instance.
	 * @return a client for it that does not follow redirects, since the tests assert on the 302 itself.
	 */
	@Bean
	public RestClient redirectApiClient(@Qualifier("redirectServiceInstance") final LocalServiceInstance instance) {
		return statusPreservingClient("http://localhost:" + instance.port(), HttpClient.Redirect.NEVER);
	}

	/**
	 * @return a client for Jaeger's query API, for reading back the traces the three services exported.
	 */
	@Bean
	public RestClient jaegerApiClient() {
		return RestClient.builder()
				.baseUrl("http://%s:%s".formatted(jaeger.getHost(), jaeger.getMappedPort(JAEGER_QUERY_PORT)))
				.build();
	}

	/**
	 * @return a template onto the Postgres container analytics-service writes to, for assertions and fault injection.
	 */
	@Bean
	public JdbcTemplate analyticsJdbcTemplate() {
		return new JdbcTemplate(new DriverManagerDataSource(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
	}

	/**
	 * @return a client onto the floci S3, for asserting on what analytics-service archived.
	 */
	@Bean(destroyMethod = "close")
	public S3Client archiveS3Client() {
		return buildS3Client();
	}

	/**
	 * @return a client onto the floci CloudWatch, for reading the HTTP response counts analytics-service publishes.
	 */
	@Bean(destroyMethod = "close")
	public CloudWatchClient flociCloudWatchClient() {
		return CloudWatchClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.build();
	}

	private static String jaegerOtlpEndpoint() {
		return "http://%s:%s/v1/traces".formatted(jaeger.getHost(), jaeger.getMappedPort(JAEGER_OTLP_HTTP_PORT));
	}

	private static RestClient statusPreservingClient(final String baseUrl, final HttpClient.Redirect redirect) {
		final HttpClient httpClient = HttpClient.newBuilder().followRedirects(redirect).build();
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(new JdkClientHttpRequestFactory(httpClient))
				// Return error responses instead of throwing, so a test can assert on a 404/400 as plainly as a 200.
				.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
				.build();
	}

}
