package io.urlshortener.analyticsservice.repository;

import io.urlshortener.eventcontracts.ClickEvent;

import java.util.List;

/**
 * Archives raw click events for later ad-hoc querying/auditing.
 */
public interface ClickEventArchiver {

	/**
	 * Archives a batch of click events. A no-op if {@code events} is empty.
	 *
	 * @param events the events to archive.
	 */
	void archive(final List<ClickEvent> events);

}
