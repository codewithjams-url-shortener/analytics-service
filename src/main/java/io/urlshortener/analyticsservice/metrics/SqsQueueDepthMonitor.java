package io.urlshortener.analyticsservice.metrics;

import io.urlshortener.analyticsservice.property.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.Objects;

/**
 * Periodically reads the {@code click-events} queue's backlog from SQS and publishes it through
 * {@link SqsConsumerMetrics}.
 *
 * <p>
 *     It runs on a schedule and the gauges read the stored values, rather than calling SQS from the gauges themselves:
 *     a gauge is evaluated on every Prometheus scrape, so reading AWS there would put a network call, and its latency
 *     and failures, into each scrape.
 * </p>
 *
 * <p>
 *     The counts are approximate by SQS's own definition. They show a backlog forming, not an exact message count.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqsQueueDepthMonitor {

	/**
	 * How often the backlog is refreshed, in milliseconds. A backlog worth alerting on lasts far longer than this.
	 */
	private static final long REFRESH_INTERVAL_MILLIS = 30_000;

	/**
	 * Reads the queue's attributes.
	 */
	private final SqsClient sqsClient;

	/**
	 * Source of the configured queue URL.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Receives the backlog, and the failure count if it cannot be read.
	 */
	private final SqsConsumerMetrics sqsConsumerMetrics;

	/**
	 * Reads the queue's visible, in-flight and delayed message counts and publishes them.
	 *
	 * <p>
	 *     A failure is logged and counted, not thrown, and the gauges keep their last values: one bad read should not
	 *     blank the backlog, and the error counter is what shows that a run of them has made it stale.
	 * </p>
	 */
	@Scheduled(fixedDelay = REFRESH_INTERVAL_MILLIS)
	public void refresh() {
		final String queueUrl = awsProperties.getSqs().getQueueUrl();
		try {
			final Map<QueueAttributeName, String> attributes = sqsClient.getQueueAttributes(
							GetQueueAttributesRequest.builder()
									.queueUrl(queueUrl)
									.attributeNames(
											QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
											QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
											QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED
									)
									.build())
					.attributes();
			final long visible = count(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
			final long inFlight = count(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);
			final long delayed = count(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED);
			sqsConsumerMetrics.updateQueueDepth(visible, inFlight, delayed);
			log.atDebug()
					.addKeyValue("queueUrl", queueUrl)
					.addKeyValue("visible", visible)
					.addKeyValue("inFlight", inFlight)
					.addKeyValue("delayed", delayed)
					.log("Refreshed the click events queue depth");
		} catch (RuntimeException e) {
			sqsConsumerMetrics.queueDepthRefreshFailed();
			log.atWarn()
					.setCause(e)
					.addKeyValue("queueUrl", queueUrl)
					.log("Failed to read the click events queue depth, keeping the last values");
		}
	}

	/**
	 * @param attributes the attributes SQS returned.
	 * @param name       the attribute to read.
	 * @return its value as a number.
	 * @throws IllegalStateException if SQS did not return the attribute, rather than reporting a made-up zero.
	 */
	private long count(final Map<QueueAttributeName, String> attributes, final QueueAttributeName name) {
		final String value = attributes.get(name);
		if (Objects.isNull(value)) {
			throw new IllegalStateException("SQS did not return the queue attribute %s".formatted(name));
		}
		return Long.parseLong(value);
	}

}
