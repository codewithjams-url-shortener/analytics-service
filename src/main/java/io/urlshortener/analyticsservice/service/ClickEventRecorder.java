package io.urlshortener.analyticsservice.service;

import io.urlshortener.analyticsservice.repository.ClickEventRepository;
import io.urlshortener.eventcontracts.ClickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Records a batch of click events and rolls them into {@code link_stats} and {@code link_daily_stats}, as a single
 * transaction.
 *
 * <p>
 *     The inserts into {@code click_events} and both rollup upserts commit or roll back together. Without that, a
 *     failure after the inserts would leave the events recorded but uncounted, and the SQS redelivery would then see
 *     them as duplicates and skip counting them for good.
 * </p>
 *
 * <p>
 *     This is a separate bean, not a private method of the consumer, because Spring applies {@code @Transactional}
 *     through a proxy and a call from within the same class would bypass it. Failures are logged with context and
 *     <em>rethrown</em>: swallowing one would let the transaction commit with partial work.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventRecorder {

	/**
	 * Inserts click events and upserts both rollup tables. Each call joins this class's transaction.
	 */
	private final ClickEventRepository clickEventRepository;

	/**
	 * Records the events, then rolls the newly recorded ones into the rollups.
	 *
	 * <p>
	 *     Only events that were not already recorded ({@link ClickEventRepository#recordIfNew}) are counted, which is
	 *     what makes SQS redelivery safe. Short codes and days are processed in sorted order, so concurrent
	 *     transactions on different pods take their row locks in the same order and cannot deadlock each other.
	 *     A day is the UTC day of the event's own timestamp, not of when it was processed, so late or redelivered events
	 *     land on the right day.
	 * </p>
	 *
	 * @param events the parsed events of one batch, possibly including duplicates of already-recorded events.
	 * @return the number of events that were new and are now counted; {@code 0} if {@code events} is empty or every
	 * event was a duplicate.
	 */
	@Transactional
	public int record(final List<ClickEvent> events) {
		log.atDebug()
				.log("Recording Click Events started");
		if (CollectionUtils.isEmpty(events)) {
			log.atInfo()
					.addKeyValue("eventsCount", 0)
					.log("No Click Events");
			return 0;
		}
		log.atDebug()
				.addKeyValue("eventsCount", events.size())
				.log("Click Events received");
		final List<ClickEvent> newEvents = new ArrayList<>();
		for (final ClickEvent event : events) {
			final boolean newRecord;
			try {
				newRecord = clickEventRepository.recordIfNew(event);
			} catch (Exception e) {
				log.atWarn()
						.setCause(e)
						.addKeyValue("eventId", event.eventId())
						.addKeyValue("shortCode", event.shortCode())
						.log("Error while checking if the Record is new");
				throw e;
			}
			if (newRecord) {
				newEvents.add(event);
			}
		}
		if (CollectionUtils.isEmpty(newEvents)) {
			log.atInfo()
					.addKeyValue("eventsCount", events.size())
					.addKeyValue("newEventsCount", 0)
					.log("No new Click Events received");
			return 0;
		}
		log.atDebug()
				.addKeyValue("eventsCount", events.size())
				.addKeyValue("newEventsCount", newEvents.size())
				.log("New Click Events filtered");
		final Map<String, List<ClickEvent>> groupByShortCode = newEvents.stream()
				.collect(Collectors.groupingBy(ClickEvent::shortCode, TreeMap::new, Collectors.toList()));
		for (final Map.Entry<String, List<ClickEvent>> shortCodeEntry : groupByShortCode.entrySet()) {
			final String shortCode = shortCodeEntry.getKey();
			final List<ClickEvent> eventsByShortCode = shortCodeEntry.getValue();
			incrementStatsPerShortCode(shortCode, eventsByShortCode);
			final Map<LocalDate, List<ClickEvent>> groupByTimestamp = eventsByShortCode.stream()
					.collect(
							Collectors.groupingBy(
									event -> Instant.ofEpochMilli(event.timestamp())
											.atZone(ZoneOffset.UTC)
											.toLocalDate(),
									TreeMap::new,
									Collectors.toList()
							)
					);
			for (final Map.Entry<LocalDate, List<ClickEvent>> timeStampEntry : groupByTimestamp.entrySet()) {
				final LocalDate date = timeStampEntry.getKey();
				final List<ClickEvent> eventsByDate = timeStampEntry.getValue();
				incrementDailyStatsPerShortCode(shortCode, date, eventsByDate);
			}
		}
		log.atInfo()
				.addKeyValue("eventsCount", events.size())
				.addKeyValue("newEventsCount", newEvents.size())
				.log("New Click Events processed");
		return newEvents.size();
	}

	/**
	 * Counts one short code's new events by outcome and adds them to its all-time {@code link_stats} row.
	 *
	 * @param shortCode the short code the events belong to.
	 * @param events    that short code's new events from this batch.
	 */
	private void incrementStatsPerShortCode(final String shortCode, final List<ClickEvent> events) {
		int resolved = 0;
		int expired = 0;
		int notFound = 0;
		long maxTimestamp = 0;
		for (final ClickEvent event : events) {
			switch (event.outcome()) {
				case RESOLVED -> resolved++;
				case EXPIRED -> expired++;
				case NOT_FOUND -> notFound++;
			}
			maxTimestamp = Math.max(maxTimestamp, event.timestamp());
		}
		final Instant lastClickedAt = Instant.ofEpochMilli(maxTimestamp);
		try {
			clickEventRepository.incrementStats(shortCode, resolved, expired, notFound, lastClickedAt);
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("resolvedEvents", resolved)
					.addKeyValue("expiredEvents", expired)
					.addKeyValue("notFoundEvents", notFound)
					.addKeyValue("totalEvents", events.size())
					.addKeyValue("lastClickedAt", lastClickedAt)
					.log("Failed to increment stats");
			throw e;
		}
		log.atDebug()
				.addKeyValue("shortCode", shortCode)
				.addKeyValue("resolvedEvents", resolved)
				.addKeyValue("expiredEvents", expired)
				.addKeyValue("notFoundEvents", notFound)
				.addKeyValue("totalEvents", events.size())
				.addKeyValue("lastClickedAt", lastClickedAt)
				.log("Incremented Events Stats");
	}

	/**
	 * Counts one short code's new events for one UTC day by outcome and adds them to its {@code link_daily_stats} row.
	 *
	 * @param shortCode the short code the events belong to.
	 * @param day       the UTC day the events occurred on.
	 * @param events    that short code's new events from this batch that occurred on {@code day}.
	 */
	private void incrementDailyStatsPerShortCode(final String shortCode, final LocalDate day,
												 final List<ClickEvent> events) {
		int resolved = 0;
		int expired = 0;
		int notFound = 0;
		for (final ClickEvent event : events) {
			switch (event.outcome()) {
				case RESOLVED -> resolved++;
				case EXPIRED -> expired++;
				case NOT_FOUND -> notFound++;
			}
		}
		try {
			clickEventRepository.incrementDailyStats(shortCode, day, resolved, expired, notFound);
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("shortCode",shortCode)
					.addKeyValue("resolvedEvents", resolved)
					.addKeyValue("expiredEvents", expired)
					.addKeyValue("notFoundEvents", notFound)
					.addKeyValue("totalEvents", events.size())
					.addKeyValue("day", day)
					.log("Failed to increment daily stats");
			throw e;
		}
		log.atDebug()
				.addKeyValue("shortCode",shortCode)
				.addKeyValue("resolvedEvents", resolved)
				.addKeyValue("expiredEvents", expired)
				.addKeyValue("notFoundEvents", notFound)
				.addKeyValue("totalEvents", events.size())
				.addKeyValue("day", day)
				.log("Incremented Events Daily Stats for the day");
	}

}
