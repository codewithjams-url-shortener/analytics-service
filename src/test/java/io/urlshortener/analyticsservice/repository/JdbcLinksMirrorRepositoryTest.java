package io.urlshortener.analyticsservice.repository;

import io.urlshortener.analyticsservice.model.domainObject.DeletionReason;
import io.urlshortener.analyticsservice.model.domainObject.MirroredLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JdbcLinksMirrorRepositoryTest {

	private static final Instant CREATED_AT = Instant.parse("2026-10-01T08:00:00Z");

	private static final Instant EXPIRES_AT = Instant.parse("2026-12-01T08:00:00Z");

	private static final Instant DELETED_AT = Instant.parse("2026-10-16T10:15:30Z");

	@Mock
	private JdbcClient jdbcClient;

	@Mock(answer = Answers.RETURNS_SELF)
	private JdbcClient.StatementSpec statementSpec;

	@Mock
	private JdbcClient.MappedQuerySpec<Boolean> existsQuery;

	private JdbcLinksMirrorRepository repository;

	@BeforeEach
	void setUp() {
		repository = new JdbcLinksMirrorRepository(jdbcClient);
		given(jdbcClient.sql(anyString())).willReturn(statementSpec);
	}

	@Test
	void upsertLink_shouldIssueAnUpsertOnShortCode_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);

		// Act
		repository.upsertLink(liveLink());

		// Assert
		final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcClient).sql(sqlCaptor.capture());
		assertThat(sqlCaptor.getValue())
				.contains("INSERT INTO links")
				.contains("ON CONFLICT (short_code) DO UPDATE");
	}

	@Test
	void upsertLink_shouldBindPlainFields_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);

		// Act
		repository.upsertLink(liveLink());

		// Assert
		verify(statementSpec).param("shortCode", "abc1234");
		verify(statementSpec).param("longUrl", "https://example.com");
		verify(statementSpec).param("ownerId", "owner-1");
		verify(statementSpec).param("status", "FLAGGED");
	}

	@Test
	void upsertLink_shouldBindTimestampsAsOffsetDateTime_notRawInstant() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final MirroredLink deleted = MirroredLink.builder()
				.shortCode("abc1234")
				.longUrl("https://example.com")
				.createdAt(CREATED_AT)
				.expiresAt(EXPIRES_AT)
				.deletedAt(DELETED_AT)
				.deletionReason(DeletionReason.EXPLICIT_DELETE)
				.build();

		// Act
		repository.upsertLink(deleted);

		// Assert
		// The PostgreSQL JDBC driver cannot bind a raw java.time.Instant.
		verify(statementSpec).param("createdAt", CREATED_AT.atOffset(ZoneOffset.UTC));
		verify(statementSpec).param("expiresAt", EXPIRES_AT.atOffset(ZoneOffset.UTC));
		verify(statementSpec).param("deletedAt", DELETED_AT.atOffset(ZoneOffset.UTC));
	}

	@Test
	void upsertLink_shouldBindDeletionReasonAsString_notRawEnum() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final MirroredLink expired = MirroredLink.builder()
				.shortCode("abc1234")
				.longUrl("https://example.com")
				.createdAt(CREATED_AT)
				.deletedAt(DELETED_AT)
				.deletionReason(DeletionReason.TTL_EXPIRY)
				.build();

		// Act
		repository.upsertLink(expired);

		// Assert
		verify(statementSpec).param("deletionReason", "TTL_EXPIRY");
	}

	@Test
	void upsertLink_shouldBindNullTimestampsAndReason_whenLinkIsLiveAndNeverExpires() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final MirroredLink link = MirroredLink.builder()
				.shortCode("abc1234")
				.longUrl("https://example.com")
				.createdAt(CREATED_AT)
				.build();

		// Act
		repository.upsertLink(link);

		// Assert
		verify(statementSpec).param("expiresAt", (Object) null);
		verify(statementSpec).param("deletedAt", (Object) null);
		verify(statementSpec).param("deletionReason", (Object) null);
	}

	@Test
	void upsertLink_shouldPropagateException_whenUpdateFails() {
		// Arrange
		given(statementSpec.update()).willThrow(new IllegalStateException("Postgres unavailable"));

		// Act & Assert
		assertThatThrownBy(() -> repository.upsertLink(liveLink()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Postgres unavailable");
	}

	@Test
	void existsByShortCode_shouldReturnTrueAndBindTheShortCode_whenTheMirrorHoldsARow() {
		// Arrange
		given(statementSpec.query(Boolean.class)).willReturn(existsQuery);
		given(existsQuery.single()).willReturn(true);

		// Act
		final boolean result = repository.existsByShortCode("abc1234");

		// Assert
		assertThat(result).isTrue();
		verify(statementSpec).param("shortCode", "abc1234");
	}

	@Test
	void existsByShortCode_shouldReturnFalse_whenTheMirrorHoldsNoRow() {
		// Arrange
		given(statementSpec.query(Boolean.class)).willReturn(existsQuery);
		given(existsQuery.single()).willReturn(false);

		// Act
		final boolean result = repository.existsByShortCode("abc1234");

		// Assert
		assertThat(result).isFalse();
	}

	@Test
	void existsByShortCode_shouldCountSoftDeletedRows_whenQueryingTheMirror() {
		// Arrange
		given(statementSpec.query(Boolean.class)).willReturn(existsQuery);
		given(existsQuery.single()).willReturn(true);

		// Act
		repository.existsByShortCode("abc1234");

		// Assert
		// A soft-deleted link keeps its click history, so the check must not filter on deleted_at.
		final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcClient).sql(sqlCaptor.capture());
		assertThat(sqlCaptor.getValue())
				.contains("FROM links")
				.contains("short_code = :shortCode")
				.doesNotContain("deleted_at");
	}

	private MirroredLink liveLink() {
		return MirroredLink.builder()
				.shortCode("abc1234")
				.longUrl("https://example.com")
				.ownerId("owner-1")
				.createdAt(CREATED_AT)
				.expiresAt(EXPIRES_AT)
				.status("FLAGGED")
				.build();
	}

}
