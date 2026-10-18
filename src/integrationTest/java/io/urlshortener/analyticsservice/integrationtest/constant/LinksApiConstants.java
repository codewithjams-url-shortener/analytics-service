package io.urlshortener.analyticsservice.integrationtest.constant;

/**
 * JSON field names and header names used when calling {@code url-service}'s {@code /links} API to create, expire and
 * remove the links whose clicks {@code analytics-service} counts.
 */
public class LinksApiConstants {

	/**
	 * Name of the {@code shortCode} field in the create response.
	 */
	public static final String FIELD_SHORT_CODE = "shortCode";

	/**
	 * Name of the {@code managementToken} field in the create response.
	 */
	public static final String FIELD_MANAGEMENT_TOKEN = "managementToken";

	/**
	 * Name of the header carrying the management token on patch/delete requests.
	 */
	public static final String HEADER_MANAGEMENT_TOKEN = "X-Management-Token";

	/**
	 * Not instantiable.
	 */
	private LinksApiConstants() {
	}

}
