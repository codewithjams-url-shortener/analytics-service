package io.urlshortener.analyticsservice.exception;

import io.urlshortener.analyticsservice.model.dataTransferObject.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Translates domain exceptions and request validation failures raised by controllers into
 * structured HTTP error responses.
 */
@Slf4j
@RestControllerAdvice
public class ControllerAdvice {

	/**
	 * Maps a path parameter validation failure (e.g. shortCode shorter than the OpenAPI-declared
	 * minLength) to a structured error response instead of Spring's default error body.
	 *
	 * @param e the validation exception raised when a path parameter fails bean validation.
	 * @return a {@code 400 Bad Request} response listing each invalid parameter and its violation.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolationError(final ConstraintViolationException e) {
		final String message = e.getConstraintViolations()
				.stream()
				.map(
						violation -> {
							final String propertyPath = violation.getPropertyPath().toString();
							final String field = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
							return "%s: %s".formatted(field, violation.getMessage());
						}
				)
				.collect(Collectors.joining(", "));
		log.atError()
				.addKeyValue("reason", message)
				.log("Request validation failed due to Input Constraint Violation");
		final ErrorResponse response = ErrorResponse.builder().message(message).build();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	/**
	 * Maps a query parameter that cannot be parsed as a date (e.g. {@code from=abc} or {@code to=2026-13-45}) to a
	 * structured error response instead of Spring's default error body.
	 *
	 * @param e the exception raised when a request parameter cannot be converted to its declared type.
	 * @return a {@code 400 Bad Request} response naming the parameter and the expected format.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentMismatchError(
			final MethodArgumentTypeMismatchException e) {
		final String message = "%s: must be a date in yyyy-MM-dd format".formatted(e.getName());
		log.atError()
				.addKeyValue("parameter", e.getName())
				.addKeyValue("value", e.getValue())
				.log("Request validation failed due to a Request Parameter of an invalid type");
		final ErrorResponse response = ErrorResponse.builder().message(message).build();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	/**
	 * Maps a rejected date range (backwards, or longer than allowed) to a structured error response.
	 *
	 * @param e the exception raised by the stats service.
	 * @return a {@code 400 Bad Request} response carrying the exception's message.
	 */
	@ExceptionHandler(InvalidDateRangeException.class)
	public ResponseEntity<ErrorResponse> handleInvalidDateRangeError(final InvalidDateRangeException e) {
		final String message = e.getMessage();
		log.atError()
				.addKeyValue("reason", message)
				.log("Request validation failed due to providing Invalid Date Range");
		final ErrorResponse response = ErrorResponse.builder().message(message).build();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	/**
	 * Maps an unknown short code to a not-found response.
	 *
	 * @param e the not-found exception raised by the stats service.
	 * @return a {@code 404 Not Found} response carrying the exception's message.
	 */
	@ExceptionHandler(ShortLinkNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleShortLinkNotFoundError(final ShortLinkNotFoundException e) {
		final String message = e.getMessage();
		log.atError()
				.addKeyValue("shortCode", e.getShortCode())
				.log("Short Code not found");
		final ErrorResponse response = ErrorResponse.builder().message(message).build();
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

}
