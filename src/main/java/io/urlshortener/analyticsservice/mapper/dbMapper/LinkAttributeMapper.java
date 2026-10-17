package io.urlshortener.analyticsservice.mapper.dbMapper;

import io.urlshortener.analyticsservice.exception.CorruptedDataException;
import io.urlshortener.linkscontract.Link;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

/**
 * Converts a raw {@link AttributeValue} map (as used by the DynamoDB SDK) into a {@link Link} DAO.
 * analytics-service only ever reads link data via stream images, so unlike url-service's own
 * version of this mapper, no write direction is needed here.
 */
@Slf4j
@Component
public class LinkAttributeMapper {

	/**
	 * Converts a raw DynamoDB item (a stream record's newImage/oldImage, or a plain GetItem result) into
	 * a {@link Link}.
	 *
	 * @param map the raw DynamoDB item.
	 * @return the equivalent {@link Link}.
	 * @throws CorruptedDataException if the required {@code createdAt} attribute is missing.
	 */
	public Link toObject(final Map<String, AttributeValue> map) {
		final String shortCode = map.containsKey("shortCode") ? map.get("shortCode").s() : null;
		final String longUrl = map.containsKey("longUrl") ? map.get("longUrl").s() : null;
		final String ownerId = map.containsKey("ownerId") ? map.get("ownerId").s() : null;

		if (!map.containsKey("createdAt")) {
			log.atError()
					.addKeyValue("field", "createdAt")
					.log("Required value missing from DB");
			throw new CorruptedDataException("Expecting value at createdAt, value is absent instead");
		}

		final long createdAt = Long.parseLong(map.get("createdAt").n());
		final Long expiresAt = map.containsKey("expiresAt") ? Long.parseLong(map.get("expiresAt").n()) : null;
		final String managementTokenHash = map.containsKey("managementTokenHash") ? map.get("managementTokenHash").s() : null;
		final String status = map.containsKey("status") ? map.get("status").s() : null;
		return new Link(shortCode, longUrl, ownerId, createdAt, expiresAt, managementTokenHash, status);
	}

}
