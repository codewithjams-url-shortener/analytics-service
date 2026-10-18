package io.urlshortener.analyticsservice.exception;

/**
 * Thrown when the requested date range of a stats request is not acceptable: backwards, or longer than allowed.
 */
public class InvalidDateRangeException extends RuntimeException {

	/**
	 * Creates the exception with a message describing why the range was rejected.
	 *
	 * @param message why the range was rejected, returned to the client.
	 */
	public InvalidDateRangeException(String message) {
		super(message);
	}

}
