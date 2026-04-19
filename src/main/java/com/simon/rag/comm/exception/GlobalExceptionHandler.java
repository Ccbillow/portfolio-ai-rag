package com.simon.rag.comm.exception;

import com.simon.rag.comm.result.Result;
import com.simon.rag.comm.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Global exception handler — catches all unhandled exceptions and returns
 * a consistent Result<> JSON body instead of Spring's default error page.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ----------------------------------------------------------------
    //  Validation errors — @Valid / @Validated on DTOs
    // ----------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBind(BindException ex) {
        String message = ex.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.error(ResultCode.BAD_REQUEST.getCode(), message);
    }

    // ----------------------------------------------------------------
    //  Security errors
    // ----------------------------------------------------------------

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuth(AuthenticationException ex) {
        return Result.error(ResultCode.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccess(AccessDeniedException ex) {
        return Result.error(ResultCode.FORBIDDEN);
    }

    // ----------------------------------------------------------------
    //  Business exceptions
    // ----------------------------------------------------------------

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusiness(BusinessException ex) {
        log.warn("Business error [{}]: {}", ex.getCode(), ex.getMessage());
        return Result.error(ex.getCode(), ex.getMessage());
    }

    // ----------------------------------------------------------------
    //  File upload too large
    // ----------------------------------------------------------------

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleFileTooLarge(MaxUploadSizeExceededException ex) {
        return Result.error(ResultCode.BAD_REQUEST.getCode(),
                "File too large — maximum upload size is 50MB");
    }

    // ----------------------------------------------------------------
    //  Catch-all — log full stack trace for unexpected errors
    // ----------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleAll(Exception ex) {
        log.error("Unhandled exception", ex);
        return Result.error(ResultCode.INTERNAL_ERROR);
    }
}