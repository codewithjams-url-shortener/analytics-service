package io.urlshortener.analyticsservice.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.eventcontracts.ClickOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class S3ClickEventArchiverTest {

	private static final String BUCKET_NAME = "url-shortener-events";

	@Mock
	private S3Client s3Client;

	private S3ClickEventArchiver archiver;

	@BeforeEach
	void setUp() {
		final AwsProperties.S3 s3 = new AwsProperties.S3();
		s3.setBucketName(BUCKET_NAME);
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setS3(s3);

		archiver = new S3ClickEventArchiver(s3Client, awsProperties, new ObjectMapper());
	}

	@Test
	void archive_shouldNotCallS3_whenEventsListIsEmpty() {
		// Act
		archiver.archive(List.of());

		// Assert
		verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	@Test
	void archive_shouldPutSingleNdjsonObjectUnderClickEventsPrefix_whenEventsProvided() throws IOException {
		// Arrange
		final ClickEvent first = clickEvent("event-1", "abc1234", ClickOutcome.RESOLVED);
		final ClickEvent second = clickEvent("event-2", "xyz7890", ClickOutcome.NOT_FOUND);
		final ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
		final ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);

		// Act
		archiver.archive(List.of(first, second));

		// Assert
		verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
		final PutObjectRequest request = requestCaptor.getValue();
		assertThat(request.bucket()).isEqualTo(BUCKET_NAME);
		assertThat(request.key())
				.startsWith("click-events/dt=")
				.endsWith(".ndjson");

		final String body = readBody(bodyCaptor.getValue());
		final String[] lines = body.split("\n");
		assertThat(lines)
				.as("one NDJSON line per event")
				.hasSize(2);
		final ObjectMapper objectMapper = new ObjectMapper();
		assertThat(objectMapper.readValue(lines[0], ClickEvent.class)).isEqualTo(first);
		assertThat(objectMapper.readValue(lines[1], ClickEvent.class)).isEqualTo(second);
	}

	@Test
	void archive_shouldPropagateException_whenS3PutObjectFails() {
		// Arrange
		given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
				.willThrow(new RuntimeException("S3 unavailable"));
		final ClickEvent event = clickEvent("event-1", "abc1234", ClickOutcome.RESOLVED);

		// Act & Assert
		assertThatThrownBy(() -> archiver.archive(List.of(event)))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("S3 unavailable");
	}

	private String readBody(final RequestBody requestBody) throws IOException {
		try (InputStream in = requestBody.contentStreamProvider().newStream()) {
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			return out.toString();
		}
	}

	private ClickEvent clickEvent(final String eventId, final String shortCode, final ClickOutcome outcome) {
		return new ClickEvent(eventId, shortCode, Instant.now().toEpochMilli(), outcome, "google.com",
				"Mozilla/5.0", "hash123");
	}

}
