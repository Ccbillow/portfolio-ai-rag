package com.simon.rag.comm.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.io.Serializable;

/**
 * Unified API response wrapper.
 *
 * <p>Convention (follows Alibaba Java Coding Standards):
 * <ul>
 *   <li>code 200  — success</li>
 *   <li>code 401  — unauthorized</li>
 *   <li>code 403  — forbidden</li>
 *   <li>code 429  — rate limit exceeded</li>
 *   <li>code 500  — internal server error</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   return Result.success(data);
 *   return Result.error("Document not found");
 *   return Result.error(ResultCode.UNAUTHORIZED);
 * </pre>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ----------------------------------------------------------------
    //  Success
    // ----------------------------------------------------------------

    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    // ----------------------------------------------------------------
    //  Error
    // ----------------------------------------------------------------

    public static <T> Result<T> error(String message) {
        return new Result<>(ResultCode.INTERNAL_ERROR.getCode(), message, null);
    }

    public static <T> Result<T> error(ResultCode code) {
        return new Result<>(code.getCode(), code.getMessage(), null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ----------------------------------------------------------------
    //  Convenience
    // ----------------------------------------------------------------

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}