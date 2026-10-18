package io.urlshortener.analyticsservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.urlshortener.analyticsservice.exception.ControllerAdvice;
import io.urlshortener.analyticsservice.exception.InvalidDateRangeException;
import io.urlshortener.analyticsservice.exception.ShortLinkNotFoundException;
import io.urlshortener.analyticsservice.mapper.responseMapper.LinkStatsResponseMapper;
import io.urlshortener.analyticsservice.model.domainObject.DailyStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStatsReport;
import io.urlshortener.analyticsservice.service.LinkStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

	private static final String SHORT_CODE = "abc1234";

	private static final LocalDate FROM = LocalDate.of(2024, 3, 18);

	private static final LocalDate TO = LocalDate.of(2024, 3, 19);

	@Mock
	private LinkStatsService linkStatsService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		// Standalone, so the real mapper and advice are exercised without starting Spring or a database. Method-level
		// bean validation of the path parameter needs the Spring proxy, so that case is covered by the advice test.
		final StatsController controller = new StatsController(linkStatsService, new LinkStatsResponseMapper());
		// A standalone MockMvc has none of Spring Boot's Jackson defaults, which write dates as ISO-8601 strings rather
		// than numeric timestamps. Match Boot so the JSON asserted here is the JSON the running service produces.
		final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
				.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.build();
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ControllerAdvice())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
				.build();
	}

	@Test
	void getLinkStats_shouldReturn200WithTheStatistics_whenTheServiceReturnsAReport() throws Exception {
		// Arrange
		final LinkStatsReport report = new LinkStatsReport(
				SHORT_CODE,
				new LinkStats(5, 2, 1, Instant.parse("2024-03-19T08:30:00Z")),
				FROM,
				TO,
				List.of(new DailyStats(FROM, 3, 1, 0), new DailyStats(TO, 2, 1, 1))
		);
		given(linkStatsService.getStats(SHORT_CODE, FROM, TO)).willReturn(report);

		// Act & Assert
		mockMvc.perform(get("/links/{shortCode}/stats", SHORT_CODE)
						.param("from", "2024-03-18")
						.param("to", "2024-03-19"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shortCode").value(SHORT_CODE))
				.andExpect(jsonPath("$.totalClicks").value(5))
				.andExpect(jsonPath("$.resolved").value(5))
				.andExpect(jsonPath("$.expired").value(2))
				.andExpect(jsonPath("$.notFound").value(1))
				.andExpect(jsonPath("$.lastClickedAt").value("2024-03-19T08:30:00Z"))
				.andExpect(jsonPath("$.from").value("2024-03-18"))
				.andExpect(jsonPath("$.to").value("2024-03-19"))
				.andExpect(jsonPath("$.daily.length()").value(2))
				.andExpect(jsonPath("$.daily[0].date").value("2024-03-18"))
				.andExpect(jsonPath("$.daily[0].clicks").value(3))
				.andExpect(jsonPath("$.daily[1].notFound").value(1));
	}

	@Test
	void getLinkStats_shouldPassNullDatesToTheService_whenNoQueryParametersAreGiven() throws Exception {
		// Arrange
		final LinkStatsReport report = new LinkStatsReport(SHORT_CODE, new LinkStats(0, 0, 0, null), FROM, TO, List.of());
		given(linkStatsService.getStats(SHORT_CODE, null, null)).willReturn(report);

		// Act & Assert
		mockMvc.perform(get("/links/{shortCode}/stats", SHORT_CODE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastClickedAt").doesNotExist());
		verify(linkStatsService).getStats(SHORT_CODE, null, null);
	}

	@Test
	void getLinkStats_shouldReturn404WithAnErrorBody_whenTheShortCodeIsUnknown() throws Exception {
		// Arrange
		given(linkStatsService.getStats(SHORT_CODE, null, null)).willThrow(new ShortLinkNotFoundException(SHORT_CODE));

		// Act & Assert
		mockMvc.perform(get("/links/{shortCode}/stats", SHORT_CODE))
				.andExpect(status().isNotFound())
				.andExpect(
						jsonPath("$.message")
								.value("Short Link: abc1234 not found")
				);
	}

	@Test
	void getLinkStats_shouldReturn400WithAnErrorBody_whenTheDateRangeIsRejected() throws Exception {
		// Arrange
		given(linkStatsService.getStats(SHORT_CODE, TO, FROM))
				.willThrow(new InvalidDateRangeException("from date is after to date"));

		// Act & Assert
		mockMvc.perform(
						get("/links/{shortCode}/stats", SHORT_CODE)
								.param("from", "2024-03-19")
								.param("to", "2024-03-18")
				)
				.andExpect(status().isBadRequest())
				.andExpect(
						jsonPath("$.message")
								.value("from date is after to date")
				);
	}

	@Test
	void getLinkStats_shouldReturn400WithoutCallingTheService_whenADateCannotBeParsed() throws Exception {
		// Act & Assert
		mockMvc.perform(
						get("/links/{shortCode}/stats", SHORT_CODE)
								.param("from", "abc")
				)
				.andExpect(status().isBadRequest())
				.andExpect(
						jsonPath("$.message")
								.value("from: must be a date in yyyy-MM-dd format")
				);
		verifyNoInteractions(linkStatsService);
	}

}
