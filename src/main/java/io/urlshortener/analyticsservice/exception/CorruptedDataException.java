package io.urlshortener.analyticsservice.exception;

/**
 * Thrown when a required attribute is missing from a persisted item, indicating the stored data
 * is no longer consistent with the schema this service expects.
 */
public class CorruptedDataException extends RuntimeException {

	/**
	 * Creates the exception with a message describing the missing/inconsistent attribute.
	 *
	 * @param message description of the inconsistency.
	 */
	public CorruptedDataException(String message) {
		super(message);
	}

}
