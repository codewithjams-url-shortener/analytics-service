package io.urlshortener.analyticsservice.service;

import io.urlshortener.analyticsservice.repository.ClickEventRepository;
import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.eventcontracts.ClickOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ClickEventRecorderTest {

	// Fixed, neutral dates rather than values derived from the clock: these tests assert exact UTC day boundaries,
	// which would flake whenever the suite ran near midnight UTC if they were based on "now".
	private static final LocalDate DAY_ONE = LocalDate.of(2024, 1, 15);

	private static final LocalDate DAY_TWO = DAY_ONE.plusDays(1);

	private static final long DAY_ONE_NOON_UTC = DAY_ONE.atTime(12, 0)
			.toInstant(ZoneOffset.UTC)
			.toEpochMilli();

	private static final long DAY_TWO_NOON_UTC = DAY_TWO.atTime(12, 0)
			.toInstant(ZoneOffset.UTC)
			.toEpochMilli();

	private static final long DAY_TWO_START_UTC = DAY_TWO.atStartOfDay()
			.toInstant(ZoneOffset.UTC)
			.toEpochMilli();

	@Mock
	private ClickEventRepository clickEventRepository;

	private ClickEventRecorder recorder;

	@BeforeEach
	void setUp() {
		recorder = new ClickEventRecorder(clickEventRepository);
	}

	@Test
	void record_shouldBeTransactional_whenInspectedByReflection() throws NoSuchMethodException {
		// Act
		final boolean transactional = ClickEventRecorder.class.getMethod("record", List.class)
				.isAnnotationPresent(Transactional.class);

		// Assert
		// The whole point of this class is that the inserts and both rollups commit or roll back together.
		// A mock cannot prove a rollback, so at least guard against the annotation being dropped.
		assertThat(transactional).isTrue();
	}

	@Test
	void record_shouldReturnZeroAndTouchNothing_whenEventsAreEmpty() {
		// Act
		final int result = recorder.record(List.of());

		// Assert
		assertThat(result).isZero();
		verifyNoInteractions(clickEventRepository);
	}

	@Test
	void record_shouldReturnZeroAndSkipRollups_whenEveryEventIsADuplicate() {
		// Arrange
		final ClickEvent event = clickEvent(
				"event-1",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		given(clickEventRepository.recordIfNew(event)).willReturn(false);

		// Act
		final int result = recorder.record(List.of(event));

		// Assert
		assertThat(result).isZero();
		verify(clickEventRepository, never())
				.incrementStats(any(), anyLong(), anyLong(), anyLong(), any());
		verify(clickEventRepository, never())
				.incrementDailyStats(any(), any(), anyLong(), anyLong(), anyLong());
	}

	@Test
	void record_shouldCountOnlyNewEvents_whenSomeEventsAreDuplicates() {
		// Arrange
		final ClickEvent fresh = clickEvent(
				"event-1",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		final ClickEvent duplicate = clickEvent(
				"event-2",
				"abc1234",
				ClickOutcome.EXPIRED,
				DAY_ONE_NOON_UTC
		);
		given(clickEventRepository.recordIfNew(fresh)).willReturn(true);
		given(clickEventRepository.recordIfNew(duplicate)).willReturn(false);

		// Act
		final int result = recorder.record(List.of(fresh, duplicate));

		// Assert
		assertThat(result).isEqualTo(1);
		// The duplicate's EXPIRED outcome must not be counted: it was already counted when first recorded.
		verify(clickEventRepository)
				.incrementStats("abc1234", 1L, 0L, 0L, Instant.ofEpochMilli(DAY_ONE_NOON_UTC));
		verify(clickEventRepository)
				.incrementDailyStats("abc1234", DAY_ONE, 1L, 0L, 0L);
	}

	@Test
	void record_shouldIncrementStatsOncePerShortCodeWithPerOutcomeCountsAndLatestTimestamp_whenEventsShareAShortCode() {
		// Arrange
		final ClickEvent resolved = clickEvent(
				"event-1",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		final ClickEvent expired = clickEvent(
				"event-2",
				"abc1234",
				ClickOutcome.EXPIRED,
				DAY_ONE_NOON_UTC + 5_000
		);
		final ClickEvent notFound = clickEvent(
				"event-3",
				"abc1234",
				ClickOutcome.NOT_FOUND,
				DAY_ONE_NOON_UTC + 1_000
		);
		final ClickEvent resolvedAgain = clickEvent(
				"event-4",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC + 2_000
		);
		allNew(resolved, expired, notFound, resolvedAgain);

		// Act
		final int result = recorder.record(List.of(resolved, expired, notFound, resolvedAgain));

		// Assert
		assertThat(result).isEqualTo(4);
		verify(clickEventRepository)
				.incrementStats("abc1234", 2L, 1L, 1L, Instant.ofEpochMilli(DAY_ONE_NOON_UTC + 5_000));
	}

	@Test
	void record_shouldIncrementDailyStatsPerUtcDay_whenEventsStraddleMidnightUtc() {
		// Arrange
		final long beforeMidnight = DAY_TWO_START_UTC - 1_000;
		final long afterMidnight = DAY_TWO_START_UTC + 1_000;
		final ClickEvent late = clickEvent("event-1", "abc1234", ClickOutcome.RESOLVED, beforeMidnight);
		final ClickEvent early = clickEvent("event-2", "abc1234", ClickOutcome.EXPIRED, afterMidnight);
		allNew(late, early);

		// Act
		recorder.record(List.of(late, early));

		// Assert
		verify(clickEventRepository)
				.incrementDailyStats("abc1234", DAY_ONE, 1L, 0L, 0L);
		verify(clickEventRepository)
				.incrementDailyStats("abc1234", DAY_TWO, 0L, 1L, 0L);
	}

	@Test
	void record_shouldBucketByTheEventsUtcDay_whenItsTimestampFallsOnADifferentLocalDay() {
		// Arrange
		// 02:00 on the second day at +05:30 is 20:30 UTC on the first day, so it belongs to the first day.
		final long timestamp = OffsetDateTime.of(DAY_TWO.atTime(2, 0), ZoneOffset.ofHoursMinutes(5, 30))
				.toInstant()
				.toEpochMilli();
		final ClickEvent event = clickEvent("event-1", "abc1234", ClickOutcome.RESOLVED, timestamp);
		allNew(event);

		// Act
		recorder.record(List.of(event));

		// Assert
		// The day comes from the event's own timestamp, never from when the batch happens to be processed.
		verify(clickEventRepository)
				.incrementDailyStats("abc1234", DAY_ONE, 1L, 0L, 0L);
	}

	@Test
	void record_shouldProcessShortCodesAndDaysInSortedOrder_whenEventsArriveUnsorted() {
		// Arrange
		final ClickEvent zzz = clickEvent(
				"event-1",
				"zzz9999",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		final ClickEvent abcLater = clickEvent(
				"event-2",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_TWO_NOON_UTC
		);
		final ClickEvent abcEarlier = clickEvent(
				"event-3",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		final ClickEvent mmm = clickEvent(
				"event-4",
				"mmm5555",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		allNew(zzz, abcLater, abcEarlier, mmm);

		// Act
		recorder.record(List.of(zzz, abcLater, abcEarlier, mmm));

		// Assert
		// A fixed lock order stops concurrent transactions on different pods deadlocking on the same rows.
		final InOrder inOrder = inOrder(clickEventRepository);
		inOrder.verify(clickEventRepository)
				.incrementStats(eq("abc1234"), anyLong(), anyLong(), anyLong(), any());
		inOrder.verify(clickEventRepository)
				.incrementDailyStats("abc1234", DAY_ONE, 1L, 0L, 0L);
		inOrder.verify(clickEventRepository)
				.incrementDailyStats("abc1234", DAY_TWO, 1L, 0L, 0L);
		inOrder.verify(clickEventRepository)
				.incrementStats(eq("mmm5555"), anyLong(), anyLong(), anyLong(), any());
		inOrder.verify(clickEventRepository)
				.incrementDailyStats("mmm5555", DAY_ONE, 1L, 0L, 0L);
		inOrder.verify(clickEventRepository)
				.incrementStats(eq("zzz9999"), anyLong(), anyLong(), anyLong(), any());
		inOrder.verify(clickEventRepository)
				.incrementDailyStats("zzz9999", DAY_ONE, 1L, 0L, 0L);
	}

	@Test
	void record_shouldRethrowAndSkipRollups_whenRecordingAnEventFails() {
		// Arrange
		final ClickEvent event = clickEvent(
				"event-1",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		given(clickEventRepository.recordIfNew(event)).willThrow(new IllegalStateException("DB unavailable"));

		// Act & Assert
		// Rethrowing is what makes the transaction roll back. Swallowing it would commit partial work.
		assertThatThrownBy(() -> recorder.record(List.of(event)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("DB unavailable");
		verify(clickEventRepository, never()).incrementStats(any(), anyLong(), anyLong(), anyLong(), any());
		verify(clickEventRepository, never()).incrementDailyStats(any(), any(), anyLong(), anyLong(), anyLong());
	}

	@Test
	void record_shouldRethrowAndSkipDailyStats_whenIncrementingStatsFails() {
		// Arrange
		final ClickEvent event = clickEvent(
				"event-1",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		allNew(event);
		doThrow(new IllegalStateException("stats failed"))
				.when(clickEventRepository).incrementStats(any(), anyLong(), anyLong(), anyLong(), any());

		// Act & Assert
		assertThatThrownBy(() -> recorder.record(List.of(event)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("stats failed");
		verify(clickEventRepository, never()).incrementDailyStats(any(), any(), anyLong(), anyLong(), anyLong());
	}

	@Test
	void record_shouldRethrow_whenIncrementingDailyStatsFails() {
		// Arrange
		final ClickEvent event = clickEvent(
				"event-1",
				"abc1234",
				ClickOutcome.RESOLVED,
				DAY_ONE_NOON_UTC
		);
		allNew(event);
		doThrow(new IllegalStateException("daily stats failed"))
				.when(clickEventRepository).incrementDailyStats(any(), any(), anyLong(), anyLong(), anyLong());

		// Act & Assert
		assertThatThrownBy(() -> recorder.record(List.of(event)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("daily stats failed");
	}

	private void allNew(final ClickEvent... events) {
		for (final ClickEvent event : events) {
			given(clickEventRepository.recordIfNew(event)).willReturn(true);
		}
	}

	private ClickEvent clickEvent(final String eventId, final String shortCode, final ClickOutcome outcome,
								  final long timestamp) {
		return new ClickEvent(
				eventId,
				shortCode,
				timestamp,
				outcome,
				"google.com",
				"Mozilla/5.0",
				"hash123"
		);
	}

}
