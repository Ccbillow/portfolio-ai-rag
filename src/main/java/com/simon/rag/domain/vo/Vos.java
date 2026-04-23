package com.simon.rag.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public final class Vos {

    private Vos() {}

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
        private double score;
        private String contentPreview;
    }

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
    public static class LoginResponse {
        private String token;
        private String tokenType;   // "Bearer"
        private String username;
        private String role;
        private long expiresInSeconds;
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
