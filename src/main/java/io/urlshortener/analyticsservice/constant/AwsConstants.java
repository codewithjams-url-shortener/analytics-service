package io.urlshortener.analyticsservice.constant;

/**
 * Keys used to look up configured resource names, e.g. from {@code aws.dynamo-db.tables}.
 */
public class AwsConstants {

	/**
	 * Key identifying the DynamoDB table storing {@link io.urlshortener.linkscontract.Link Link} items.
	 */
	public static final String TABLE_LINKS = "links-table";

	/**
	 * Not instantiable.
	 */
	private AwsConstants() {
	}

}

