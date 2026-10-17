package io.urlshortener.analyticsservice.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.eventcontracts.LinkLifecycleEvent;
import io.urlshortener.eventcontracts.LinkLifecycleEventType;
import io.urlshortener.linkscontract.Link;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class S3LinkLifecycleEventArchiverTest {

	private static final String BUCKET_NAME = "url-shortener-events";

	@Mock
	private S3Client s3Client;

	private S3LinkLifecycleEventArchiver archiver;

	@BeforeEach
	void setUp() {
		final AwsProperties.S3 s3 = new AwsProperties.S3();
		s3.setBucketName(BUCKET_NAME);
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setS3(s3);

		archiver = new S3LinkLifecycleEventArchiver(s3Client, awsProperties, new ObjectMapper());
	}

	@Test
	void archive_shouldNotCallS3_whenEventsListIsEmpty() {
		// Act
		archiver.archive(List.of());

		// Assert
		verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	@Test
	void archive_shouldPutSingleNdjsonObjectUnderLinksLifecyclePrefix_whenEventsProvided() throws IOException {
		// Arrange
		final LinkLifecycleEvent first =
				event(LinkLifecycleEventType.CREATED, null, link("abc1234"), "seq-1");
		final LinkLifecycleEvent second =
				event(LinkLifecycleEventType.EXPIRED, link("xyz7890"), null, "seq-2");
		final ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
		final ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);

		// Act
		archiver.archive(List.of(first, second));

		// Assert
		verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
		final PutObjectRequest request = requestCaptor.getValue();
		assertThat(request.bucket()).isEqualTo(BUCKET_NAME);
		assertThat(request.key())
				.startsWith("links-lifecycle/dt=")
				.endsWith(".ndjson");

		final String[] lines = readBody(bodyCaptor.getValue()).split("\n");
		assertThat(lines)
				.as("one NDJSON line per event")
				.hasSize(2);
		final ObjectMapper objectMapper = new ObjectMapper();
		assertThat(objectMapper.readValue(lines[0], LinkLifecycleEvent.class)).isEqualTo(first);
		assertThat(objectMapper.readValue(lines[1], LinkLifecycleEvent.class)).isEqualTo(second);
	}

	@Test
	void archive_shouldWriteADifferentObjectEachTime_whenTheSameBatchIsArchivedTwice() {
		// Arrange
		final List<LinkLifecycleEvent> batch = List.of(
				event(LinkLifecycleEventType.CREATED, null, link("abc1234"), "seq-1"));
		final ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

		// Act
		archiver.archive(batch);
		archiver.archive(batch);

		// Assert
		// A replayed batch must add a second object rather than overwrite the first.
		verify(s3Client, times(2)).putObject(requestCaptor.capture(), any(RequestBody.class));
		assertThat(requestCaptor.getAllValues())
				.extracting(PutObjectRequest::key)
				.doesNotHaveDuplicates();
	}

	@Test
	void archive_shouldPropagateException_whenS3PutObjectFails() {
		// Arrange
		given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
				.willThrow(new RuntimeException("S3 unavailable"));
		final LinkLifecycleEvent event =
				event(LinkLifecycleEventType.CREATED, null, link("abc1234"), "seq-1");

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

	private LinkLifecycleEvent event(final LinkLifecycleEventType type, final Link oldLink, final Link newLink,
									 final String sequenceNumber) {
		return new LinkLifecycleEvent(type, oldLink, newLink, sequenceNumber);
	}

	private Link link(final String shortCode) {
		return new Link(
				shortCode,
				"https://example.com",
				"owner-1",
				1_700_000_000_000L,
				1_800_000_000L,
				"hash",
				null
		);
	}

}
