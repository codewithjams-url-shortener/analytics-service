package io.urlshortener.analyticsservice.mapper.dbMapper;

import io.urlshortener.analyticsservice.model.domainObject.DeletionReason;
import io.urlshortener.analyticsservice.model.domainObject.MirroredLink;
import io.urlshortener.linkscontract.Link;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LinkMirrorMapperTest {

	private final LinkMirrorMapper mapper = new LinkMirrorMapper();

	@Test
	void toDomain_shouldCopyLinkFieldsAndLeaveItLive_whenNoDeletionIsGiven() {
		// Arrange
		final Link link = new Link(
				"abc1234",
				"https://example.com",
				"owner-1",
				1_700_000_000_000L,
				1_800_000_000L,
				"hash",
				"FLAGGED"
		);

		// Act
		final MirroredLink result = mapper.toDomain(link, null, null);

		// Assert
		assertThat(result.shortCode()).isEqualTo("abc1234");
		assertThat(result.longUrl()).isEqualTo("https://example.com");
		assertThat(result.ownerId()).isEqualTo("owner-1");
		assertThat(result.status()).isEqualTo("FLAGGED");
		assertThat(result.deletedAt()).isNull();
		assertThat(result.deletionReason()).isNull();
	}

	@Test
	void toDomain_shouldReadCreatedAtAsMillisAndExpiresAtAsSeconds_whenLinkHasBoth() {
		// Arrange
		final Link link = new Link(
				"abc1234",
				"https://example.com",
				null,
				1_700_000_000_000L,
				1_800_000_000L,
				"hash",
				null
		);

		// Act
		final MirroredLink result = mapper.toDomain(link, null, null);

		// Assert
		// Link stores createdAt in epoch milliseconds but expiresAt (the DynamoDB TTL attribute) in epoch seconds.
		assertThat(result.createdAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
		assertThat(result.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
	}

	@Test
	void toDomain_shouldLeaveExpiresAtNull_whenLinkNeverExpires() {
		// Arrange
		final Link link = new Link(
				"abc1234",
				"https://example.com",
				null,
				1_700_000_000_000L,
				null,
				"hash",
				null
		);

		// Act
		final MirroredLink result = mapper.toDomain(link, null, null);

		// Assert
		assertThat(result.expiresAt()).isNull();
	}

	@Test
	void toDomain_shouldCarryDeletionDetails_whenDeletedAtAndReasonAreGiven() {
		// Arrange
		final Link link = new Link(
				"abc1234",
				"https://example.com",
				null,
				1_700_000_000_000L,
				null,
				"hash",
				null
		);
		final Instant deletedAt = Instant.parse("2026-10-16T10:15:30Z");

		// Act
		final MirroredLink result = mapper.toDomain(link, deletedAt, DeletionReason.TTL_EXPIRY);

		// Assert
		assertThat(result.deletedAt()).isEqualTo(deletedAt);
		assertThat(result.deletionReason()).isEqualTo(DeletionReason.TTL_EXPIRY);
	}

}
