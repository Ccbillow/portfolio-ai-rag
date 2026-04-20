package com.simon.rag.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * VOs (View Objects) — used for API response serialization.
 */
public final class Vos {

    private Vos() {}

    // ----------------------------------------------------------------
    //  Auth
    // ----------------------------------------------------------------

    @Data
    @Builder
    public static class LoginResponse {
        private String token;
        private String username;
        private String role;
        private long expiresIn;   // seconds until token expiry
    }

    // ----------------------------------------------------------------
    //  Chat
    // ----------------------------------------------------------------

    @Data
    @Builder
    public static class ChatResponse {
        private String answer;
        private List<SourceChunk> sources;
        private String modelUsed;
        private long latencyMs;
    }

    @Data
    @Builder
    public static class SourceChunk {
        private Long documentId;
        private String fileName;
        private String category;
        private double score;          // cosine similarity
        private String contentPreview; // first 200 chars of chunk
    }

    // ----------------------------------------------------------------
    //  Document
    // ----------------------------------------------------------------

    @Data
    @Builder
    public static class DocumentResponse {
        private Long id;
        private String fileName;
        private String fileType;
        private String category;
        private Integer chunkCount;
        private String status;
        private String taskId;
        private LocalDateTime createdAt;
        private String message;
    }

    @Data
    @Builder
    public static class IngestTaskResponse {
        private String taskId;
        private String status;    // PENDING | PROCESSING | COMPLETED | FAILED
        private int progress;     // 0-100
        private String message;
    }
}