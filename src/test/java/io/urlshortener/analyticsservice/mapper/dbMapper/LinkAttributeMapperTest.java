package io.urlshortener.analyticsservice.mapper.dbMapper;

import io.urlshortener.analyticsservice.exception.CorruptedDataException;
import io.urlshortener.linkscontract.Link;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkAttributeMapperTest {

	private final LinkAttributeMapper mapper = new LinkAttributeMapper();

	@Test
	void toObject_shouldReadAllFields_whenAttributeMapHasEveryFieldPresent() {
		// Arrange
		final Map<String, AttributeValue> attributeMap = requiredAttributes();
		attributeMap.put("ownerId", AttributeValue.builder().s("owner-1").build());
		attributeMap.put("expiresAt", AttributeValue.builder().n("2000").build());
		attributeMap.put("managementTokenHash", AttributeValue.builder().s("hash").build());
		attributeMap.put("status", AttributeValue.builder().s("FLAGGED").build());

		// Act
		final Link link = mapper.toObject(attributeMap);

		// Assert
		assertThat(link).isEqualTo(
				new Link(
						"abc1234",
						"https://example.com",
						"owner-1",
						1000L,
						2000L,
						"hash",
						"FLAGGED"
				)
		);
	}

	@Test
	void toObject_shouldLeaveOptionalFieldsNull_whenTheyAreAbsent() {
		// Arrange
		final Map<String, AttributeValue> attributeMap = requiredAttributes();

		// Act
		final Link link = mapper.toObject(attributeMap);

		// Assert
		assertThat(link.ownerId()).isNull();
		assertThat(link.expiresAt()).isNull();
		assertThat(link.managementTokenHash()).isNull();
		assertThat(link.status()).isNull();
	}

	@Test
	void toObject_shouldThrowCorruptedDataException_whenCreatedAtIsMissing() {
		// Arrange
		final Map<String, AttributeValue> attributeMap = requiredAttributes();
		attributeMap.remove("createdAt");

		// Act & Assert
		assertThatThrownBy(() -> mapper.toObject(attributeMap))
				.isInstanceOf(CorruptedDataException.class)
				.hasMessageContaining("createdAt");
	}

	private Map<String, AttributeValue> requiredAttributes() {
		final Map<String, AttributeValue> attributeMap = new HashMap<>();
		attributeMap.put("shortCode", AttributeValue.builder().s("abc1234").build());
		attributeMap.put("longUrl", AttributeValue.builder().s("https://example.com").build());
		attributeMap.put("createdAt", AttributeValue.builder().n("1000").build());
		return attributeMap;
	}

}
