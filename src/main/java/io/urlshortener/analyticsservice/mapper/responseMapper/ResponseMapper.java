package io.urlshortener.analyticsservice.mapper.responseMapper;

/**
 * Converts a domain (or domain-derived) result into an outgoing response DTO, at the
 * service/controller boundary.
 *
 * @param <T> the source domain/result type.
 * @param <D> the response DTO type.
 */
public interface ResponseMapper<T, D> {

	/**
	 * Converts a domain/result instance into its response DTO representation.
	 *
	 * @param domain the domain/result instance to convert.
	 * @return the equivalent response DTO.
	 */
	D toDto(final T domain);

}
