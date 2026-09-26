package io.urlshortener.analyticsservice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.eventcontracts.ClickEvent;
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
 * {@link ClickEventArchiver} backed by S3: one newline-delimited JSON (NDJSON) object per batch, partitioned by date.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3ClickEventArchiver implements ClickEventArchiver {

	/**
	 * Writes the archived batch object.
	 */
	private final S3Client s3Client;

	/**
	 * Source of the configured archive bucket name.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Serializes each {@link ClickEvent} to a line of the NDJSON batch object.
	 */
	private final ObjectMapper objectMapper;

	/**
	 * Archives a batch of click events as a single NDJSON object under {@code click-events/dt=<date>/}.
	 * A no-op if {@code events} is empty, so an idle poll cycle never writes an empty object.
	 *
	 * @param events the events to archive.
	 */
	@Override
	public void archive(final List<ClickEvent> events) {
		log.atDebug()
				.addKeyValue("status", "STARTED")
				.log("Starting Event Archival");

		if (CollectionUtils.isEmpty(events)) {
			log.atDebug()
					.addKeyValue("status", "ABORTED")
					.log("No Events to Archive");
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
					.log("Failed to Archive Events");
			throw e;
		}

		log.atInfo()
				.addKeyValue("key", key)
				.addKeyValue("eventCount", events.size())
				.log("Archived click events batch to S3");
	}

	/**
	 * Builds the S3 key for a batch, partitioned by today's date (UTC) with a random suffix so
	 * concurrent/repeated batches never collide.
	 *
	 * @return the key to archive this batch under.
	 */
	private String buildKey() {
		final String date = LocalDate.now(ZoneOffset.UTC).toString();
		return "click-events/dt=%s/%s.ndjson".formatted(date, UUID.randomUUID());
	}

	/**
	 * Serializes a single {@link ClickEvent} to one line of the NDJSON body.
	 *
	 * @param event the event to serialize.
	 * @return the event's JSON representation.
	 */
	private String toJson(final ClickEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException e) {
			log.atInfo()
					.setCause(e)
					.log("Failed to parse ClickEvent during Archival");
			throw new UncheckedIOException(e);
		}
	}

}
