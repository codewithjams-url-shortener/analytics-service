package io.urlshortener.analyticsservice.exception;

import io.urlshortener.analyticsservice.model.dataTransferObject.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControllerAdviceTest {

	private final ControllerAdvice controllerAdvice = new ControllerAdvice();

	@Test
	void handleConstraintViolationError_shouldReturn400WithFieldAndMessage_whenShortCodeFailsValidation() {
		// Arrange
		final ConstraintViolationException exception = constraintViolationException(
				constraintViolation("getLinkStats.shortCode", "size must be between 3 and 2147483647")
		);

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleConstraintViolationError(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getMessage()).isEqualTo("shortCode: size must be between 3 and 2147483647");
	}

	@Test
	void handleConstraintViolationError_shouldJoinAllViolations_whenMultipleParametersFailValidation() {
		// Arrange
		final ConstraintViolationException exception = constraintViolationException(
				constraintViolation("getLinkStats.shortCode", "size must be between 3 and 2147483647"),
				constraintViolation("getLinkStats.other", "must not be blank")
		);

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleConstraintViolationError(exception);

		// Assert
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getMessage())
				.contains("shortCode: size must be between 3 and 2147483647")
				.contains("other: must not be blank")
				.contains(", ");
	}

	@Test
	void handleMethodArgumentMismatchError_shouldReturn400NamingTheParameter_whenADateCannotBeParsed() {
		// Arrange
		final MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
				"abc", LocalDate.class, "from", null, new IllegalArgumentException("not a date"));

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleMethodArgumentMismatchError(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getMessage()).isEqualTo("from: must be a date in yyyy-MM-dd format");
	}

	@Test
	void handleInvalidDateRangeError_shouldReturn400WithTheExceptionMessage_whenTheRangeIsRejected() {
		// Arrange
		final InvalidDateRangeException exception = new InvalidDateRangeException("from date is after to date");

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleInvalidDateRangeError(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getMessage()).isEqualTo("from date is after to date");
	}

	@Test
	void handleShortLinkNotFoundError_shouldReturn404WithTheExceptionMessage_whenTheShortCodeIsUnknown() {
		// Arrange
		final ShortLinkNotFoundException exception = new ShortLinkNotFoundException("missing");

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleShortLinkNotFoundError(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getMessage()).isEqualTo("Short Link: missing not found");
	}

	private ConstraintViolationException constraintViolationException(final ConstraintViolation<?>... violations) {
		final Set<ConstraintViolation<?>> violationSet = new LinkedHashSet<>(Arrays.asList(violations));
		return new ConstraintViolationException(violationSet);
	}

	@SuppressWarnings("unchecked")
	private ConstraintViolation<?> constraintViolation(final String propertyPath, final String message) {
		final ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
		final Path path = mock(Path.class);
		when(path.toString()).thenReturn(propertyPath);
		when(violation.getPropertyPath()).thenReturn(path);
		when(violation.getMessage()).thenReturn(message);
		return violation;
	}

}
