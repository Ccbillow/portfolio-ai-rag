package com.simon.rag.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class Dtos {

    private Dtos() {}

    @Data
    public static class ChatRequest {

        @NotBlank(message = "Question must not be blank")
        @Size(max = 2000, message = "Question must not exceed 2000 characters")
        private String question;

        /** Optional — filter retrieval to a specific knowledge category */
        private String category;

        /** Optional — session ID for future multi-turn conversation grouping */
        private String sessionId;
    }

    @Data
    public static class DocumentUploadRequest {

        @NotBlank(message = "Category must not be blank")
        private String category;   // PROJECT_EXPERIENCE | STUDY_NOTES | CODE_SAMPLE

        @Size(max = 500, message = "Description must not exceed 500 characters")
        private String description;
    }
}
