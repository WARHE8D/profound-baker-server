package com.sugarcrumbs.common.web.error;

import com.sugarcrumbs.common.exception.ConflictException;
import com.sugarcrumbs.common.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Central place every controller's exceptions funnel through, so every
 * endpoint returns the same {@link ApiError} shape.
 * <p>
 * Extends {@link ResponseEntityExceptionHandler} so every Spring MVC exception
 * (type mismatch, missing parameter/part, unreadable body, unknown path,
 * wrong method or media type, upload too large, …) keeps its proper 4xx status
 * instead of falling through to the catch-all 500; {@link #handleExceptionInternal}
 * converts Spring's {@code ProblemDetail} into {@link ApiError}.
 * <p>
 * Client-facing messages never carry stack traces or SQL. 5xx responses are
 * logged at WARN with the stack trace (CLAUDE.md §6).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Not authenticated: no or an invalid/expired bearer token (routed here by the security
     * filter chain's entry point), or bad credentials from login/refresh. Only messages our own
     * code wrote ({@link BadCredentialsException}) reach the client; token-decoding failures
     * describe the token and its validation, so they get a generic message.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        String message = ex instanceof BadCredentialsException ? ex.getMessage() : "a valid access token is required";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ApiError.of(401, HttpStatus.UNAUTHORIZED.getReasonPhrase(), message, request.getRequestURI()));
    }

    /** Authenticated, but without the role the route needs. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "you are not allowed to access this resource", request);
    }

    /**
     * Someone else changed or locked the same row first: an optimistic {@code @Version}
     * mismatch (e.g. two concurrent stock updates), or a pessimistic lock/deadlock loser.
     * The request itself was fine, so the client may simply retry.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> handleConcurrencyFailure(ConcurrencyFailureException ex, HttpServletRequest request) {
        log.debug("Concurrent modification on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, "the resource was changed by another request; reload and try again", request);
    }

    /** Thrown by domain entities/services for business-rule violations (bad state transitions, invalid values). */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> handleDomainViolation(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** A unique-constraint race lost at the DB level (e.g. two simultaneous category creations with the same name). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        // The cause names the constraint and SQL; useful in logs, never in the response.
        log.debug("Data integrity violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, "the request conflicts with existing data", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.warn("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        // Deliberately generic: the detail is in the log above, not in the response body.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "an unexpected error occurred", request);
    }

    /** Bean Validation failures on a {@code @Valid @RequestBody}: list every rejected field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        ApiError body = ApiError.withFieldErrors(
                HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "validation failed", path(request), fieldErrors);
        return ResponseEntity.badRequest().headers(headers).body(body);
    }

    /** Every other Spring MVC exception: keep Spring's status, reshape its body into {@link ApiError}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.warn("Request failed on {}", path(request), ex);
        }
        String message = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : reasonPhrase(statusCode);
        ApiError apiError = ApiError.of(statusCode.value(), reasonPhrase(statusCode), message, path(request));
        return ResponseEntity.status(statusCode).headers(headers).body(apiError);
    }

    private static Map<String, String> toFieldError(FieldError fieldError) {
        return Map.of("field", fieldError.getField(), "message", String.valueOf(fieldError.getDefaultMessage()));
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    private static String reasonPhrase(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.getReasonPhrase() : String.valueOf(statusCode.value());
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }
}
