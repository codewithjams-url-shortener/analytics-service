package io.urlshortener.analyticsservice.controller;

import io.urlshortener.analyticsservice.mapper.responseMapper.LinkStatsResponseMapper;
import io.urlshortener.analyticsservice.model.dataTransferObject.LinkStatsResponse;
import io.urlshortener.analyticsservice.model.domainObject.LinkStatsReport;
import io.urlshortener.analyticsservice.service.LinkStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller implementing the {@code /links/{shortCode}/stats} API contract defined in
 * {@code links-shortCode-stats.yaml}.
 */
@RestController
@RequiredArgsConstructor
public class StatsController implements StatsApi {

	/**
	 * Builds the statistics report.
	 */
	private final LinkStatsService linkStatsService;

	/**
	 * Converts the report into the response DTO.
	 */
	private final LinkStatsResponseMapper linkStatsResponseMapper;

	/**
	 * Returns a short link's click statistics.
	 *
	 * @param shortCode the short code to report on.
	 * @param from      the first UTC day of the daily breakdown, or {@code null} for the default.
	 * @param to        the last UTC day of the daily breakdown, or {@code null} for today.
	 * @return {@code 200 OK} with the statistics.
	 */
	@Override
	public ResponseEntity<LinkStatsResponse> getLinkStats(final String shortCode, final LocalDate from,
														  final LocalDate to) {
		final LinkStatsReport report = linkStatsService.getStats(shortCode, from, to);
		final LinkStatsResponse response = linkStatsResponseMapper.toDto(report);
		return ResponseEntity.ok(response);
	}

}
