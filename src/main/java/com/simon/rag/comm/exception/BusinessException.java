package com.simon.rag.comm.exception;

import com.simon.rag.comm.result.ResultCode;
import lombok.Getter;

/**
 * Custom business exception.
 *
 * <p>Usage:
 * <pre>
 *   throw new BusinessException(ResultCode.DOCUMENT_PARSE_FAILED);
 *   throw new BusinessException(1001, "Custom message");
 * </pre>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_ERROR.getCode();
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}