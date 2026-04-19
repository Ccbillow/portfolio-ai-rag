package com.simon.rag.comm.result;

import lombok.Getter;

/**
 * Standardized response codes.
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "Bad request"),
    UNAUTHORIZED(401, "Unauthorized — please login"),
    FORBIDDEN(403, "Forbidden — insufficient permissions"),
    NOT_FOUND(404, "Resource not found"),
    RATE_LIMITED(429, "Too many requests — please slow down"),
    INTERNAL_ERROR(500, "Internal server error"),

    // Business codes (1xxx)
    DOCUMENT_PARSE_FAILED(1001, "Document parsing failed"),
    EMBEDDING_FAILED(1002, "Embedding generation failed"),
    LLM_UNAVAILABLE(1003, "AI service temporarily unavailable"),
    DOCUMENT_ALREADY_EXISTS(1004, "Document already exists");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}