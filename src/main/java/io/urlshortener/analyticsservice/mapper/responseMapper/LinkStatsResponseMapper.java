package io.urlshortener.analyticsservice.mapper.responseMapper;

import io.urlshortener.analyticsservice.model.dataTransferObject.DailyLinkStats;
import io.urlshortener.analyticsservice.model.dataTransferObject.LinkStatsResponse;
import io.urlshortener.analyticsservice.model.domainObject.LinkStatsReport;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Converts a {@link LinkStatsReport} into the outgoing {@link LinkStatsResponse}.
 */
@Component
public class LinkStatsResponseMapper implements ResponseMapper<LinkStatsReport, LinkStatsResponse> {

	/**
	 * Converts a report into its response.
	 *
	 * <p>
	 *     {@code totalClicks} and each day's {@code clicks} are the RESOLVED count, the same as {@code resolved}.
	 *     {@code lastClickedAt} is converted to UTC and left {@code null} for a link that has never been clicked.
	 * </p>
	 *
	 * @param domain the report to convert.
	 * @return the response DTO.
	 */
	@Override
	public LinkStatsResponse toDto(final LinkStatsReport domain) {
		return LinkStatsResponse.builder()
				.shortCode(domain.shortCode())
				.from(domain.from())
				.to(domain.to())
				.totalClicks(domain.totals().resolved())
				.resolved(domain.totals().resolved())
				.expired(domain.totals().expired())
				.notFound(domain.totals().notFound())
				.lastClickedAt(
						Optional.ofNullable(domain.totals().lastClickedAt())
								.map(i -> OffsetDateTime.ofInstant(i, ZoneOffset.UTC))
								.orElse(null)
				)
				.daily(
						domain.daily()
								.stream()
								.map(
										d -> DailyLinkStats.builder()
												.date(d.day())
												.clicks(d.resolved())
												.resolved(d.resolved())
												.expired(d.expired())
												.notFound(d.notFound())
												.build()
								)
								.toList()
				)
				.build();
	}

}
