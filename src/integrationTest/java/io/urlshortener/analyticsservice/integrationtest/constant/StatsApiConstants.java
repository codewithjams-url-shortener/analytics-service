package io.urlshortener.analyticsservice.integrationtest.constant;

/**
 * JSON field names and query parameters of {@code analytics-service}'s {@code GET /links/{shortCode}/stats} response.
 *
 * <p>
 *     Handled as raw JSON ({@code JsonNode}) rather than the generated DTO, so this suite has no compile-time
 *     dependency on analytics-service and verifies the wire contract actually served.
 * </p>
 */
public class StatsApiConstants {

	/**
	 * Name of the all-time RESOLVED click count field.
	 */
	public static final String FIELD_TOTAL_CLICKS = "totalClicks";

	/**
	 * Name of the all-time RESOLVED click count field, as an outcome.
	 */
	public static final String FIELD_RESOLVED = "resolved";

	/**
	 * Name of the all-time EXPIRED click count field.
	 */
	public static final String FIELD_EXPIRED = "expired";

	/**
	 * Name of the all-time NOT_FOUND click count field.
	 */
	public static final String FIELD_NOT_FOUND = "notFound";

	/**
	 * Name of the last-click timestamp field.
	 */
	public static final String FIELD_LAST_CLICKED_AT = "lastClickedAt";

	/**
	 * Name of the first day of the daily breakdown.
	 */
	public static final String FIELD_FROM = "from";

	/**
	 * Name of the last day of the daily breakdown.
	 */
	public static final String FIELD_TO = "to";

	/**
	 * Name of the daily breakdown array.
	 */
	public static final String FIELD_DAILY = "daily";

	/**
	 * Name of the per-day RESOLVED click count field.
	 */
	public static final String FIELD_CLICKS = "clicks";

	/**
	 * Name of the error message field.
	 */
	public static final String FIELD_MESSAGE = "message";

	/**
	 * Not instantiable.
	 */
	private StatsApiConstants() {
	}

}
