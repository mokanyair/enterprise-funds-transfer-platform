package com.enterprise.funds.transfer.web;

import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.web.dto.ErrorDto;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One place that turns every failure into the standard error body. Messages are safe for display and never carry
 * internals; stack traces go to the log only, keyed by the correlation id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorFactory errors;

    public GlobalExceptionHandler(ErrorFactory errors) {
        this.errors = errors;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorDto> api(ApiException e) {
        return respond(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorDto> invalidBody(MethodArgumentNotValidException e) {
        ApiException api = new ApiException(ErrorCode.INVALID_REQUEST);
        e.getBindingResult().getFieldErrors().forEach(f -> api.detail(f.getField(), issueFor(f.getCode())));
        return respond(api);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorDto> invalidParameter(HandlerMethodValidationException e) {
        ApiException api = new ApiException(ErrorCode.INVALID_REQUEST);
        e.getParameterValidationResults().forEach(r -> api.detail(
                r.getMethodParameter().getParameterName() == null ? "parameter" : r.getMethodParameter().getParameterName(),
                "invalid value"));
        return respond(api);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorDto> constraint(ConstraintViolationException e) {
        ApiException api = new ApiException(ErrorCode.INVALID_REQUEST);
        e.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            api.detail(path.substring(path.lastIndexOf('.') + 1), "invalid value");
        });
        return respond(api);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorDto> typeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(new ApiException(ErrorCode.INVALID_REQUEST).detail(e.getName(), "invalid value"));
    }

    @ExceptionHandler(ServletRequestBindingException.class) // missing header or parameter
    ResponseEntity<ErrorDto> binding(ServletRequestBindingException e) {
        return respond(new ApiException(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class) // malformed JSON, unknown property, wrong type
    ResponseEntity<ErrorDto> unreadable(HttpMessageNotReadableException e) {
        return respond(new ApiException(ErrorCode.INVALID_REQUEST, "The request body is not valid."));
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeException.class})
    ResponseEntity<ErrorDto> unsupported(Exception e) { // the contract defines no 405 or 415
        return respond(new ApiException(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorDto> noRoute(NoResourceFoundException e) {
        return respond(new ApiException(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorDto> unexpected(Exception e) {
        LOG.error("Unhandled error (correlationId={})", CorrelationIdFilter.current(), e);
        return respond(new ApiException(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ErrorDto> respond(ApiException e) {
        HttpHeaders headers = new HttpHeaders();
        e.headers().forEach(headers::set);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(HttpStatus.valueOf(e.status())).headers(headers).body(errors.body(e));
    }

    private static String issueFor(String constraintCode) {
        if (constraintCode == null) {
            return "invalid value";
        }
        return switch (constraintCode) {
            case "NotNull", "NotBlank" -> "required";
            case "Pattern" -> "invalid format";
            case "Size" -> "too long";
            default -> "invalid value";
        };
    }
}
