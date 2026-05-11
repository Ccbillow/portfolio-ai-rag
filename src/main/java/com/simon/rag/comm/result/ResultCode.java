package com.simon.rag.comm.result;

import lombok.Getter;

/**
 * Standardized response codes.
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "Bad request"),
    UNAUTHORIZED(401, "Session expired — please refresh the page"),
    FORBIDDEN(403, "Access denied"),
    NOT_FOUND(404, "Resource not found"),
    RATE_LIMITED(429, "You're asking too fast — please slow down a little!"),
    DAILY_RATE_LIMITED(429, "You've reached today's question limit. Come back tomorrow!"),
    INTERNAL_ERROR(500, "Something went wrong — please try again"),

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