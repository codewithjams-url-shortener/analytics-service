package io.urlshortener.analyticsservice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.eventcontracts.LinkLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link LinkLifecycleEventArchiver} that writes each batch to S3 as one newline-delimited JSON object.
 *
 * <p>
 *     Objects are laid out as {@code links-lifecycle/dt=YYYY-MM-DD/<uuid>.ndjson} (Hive-style date partitioning, so Athena
 *     can prune by date). The key is random rather than derived from the batch, so a replayed batch writes a second object
 *     instead of overwriting the first.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3LinkLifecycleEventArchiver implements LinkLifecycleEventArchiver {

	/**
	 * Writes the archive objects.
	 */
	private final S3Client s3Client;

	/**
	 * Source of the configured bucket name.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Serialises each event to JSON.
	 */
	private final ObjectMapper objectMapper;

	/**
	 * {@inheritDoc}
	 *
	 * @throws java.io.UncheckedIOException if an event cannot be serialised.
	 */
	@Override
	public void archive(final List<LinkLifecycleEvent> events) {
		log.atDebug()
				.addKeyValue("status", "STARTED")
				.log("Starting Link Lifecycle Event Archival");

		if (CollectionUtils.isEmpty(events)) {
			log.atDebug()
					.addKeyValue("status", "ABORTED")
					.log("No Link Lifecycle Event to Archive");
			return;
		}

		final String key = buildKey();
		final String body = events.stream()
				.map(this::toJson)
				.collect(Collectors.joining("\n"));

		final PutObjectRequest request = PutObjectRequest.builder()
				.bucket(awsProperties.getS3().getBucketName())
				.key(key)
				.build();

		try {
			s3Client.putObject(request, RequestBody.fromString(body));
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("status", "FAILED")
					.addKeyValue("key", key)
					.log("Failed to Archive Link Lifecycle Event");
			throw e;
		}

		log.atInfo()
				.addKeyValue("key", key)
				.addKeyValue("eventCount", events.size())
				.addKeyValue("status", "SUCCESS")
				.log("Archived Link Lifecycle Event batch to S3");
	}

	/**
	 * @return a unique object key under today's (UTC) date partition.
	 */
	private String buildKey() {
		final String date = LocalDate.now(ZoneOffset.UTC).toString();
		return "links-lifecycle/dt=%s/%s.ndjson".formatted(date, UUID.randomUUID());
	}

	/**
	 * @param event the event to serialise.
	 * @return the event as a single line of JSON.
	 * @throws UncheckedIOException if the event cannot be serialised.
	 */
	private String toJson(final LinkLifecycleEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException e) {
			log.atInfo()
					.setCause(e)
					.log("Failed to parse Link Lifecycle Event during Archival");
			throw new UncheckedIOException(e);
		}
	}

}
