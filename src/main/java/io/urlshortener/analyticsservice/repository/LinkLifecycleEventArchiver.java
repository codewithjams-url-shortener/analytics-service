package io.urlshortener.analyticsservice.repository;

import io.urlshortener.eventcontracts.LinkLifecycleEvent;

import java.util.List;

/**
 * Archives link lifecycle events for later ad-hoc querying/auditing.
 */
public interface LinkLifecycleEventArchiver {

	/**
	 * Archives a batch of lifecycle events. A no-op if {@code events} is empty.
	 *
	 * <p>
	 *     A replayed batch is archived again, so the archive can hold duplicates. Consumers should dedupe on
	 *     {@code sequenceNumber}.
	 * </p>
	 *
	 * @param events the events to archive.
	 */
	void archive(final List<LinkLifecycleEvent> events);

}
