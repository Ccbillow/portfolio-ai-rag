package com.simon.rag.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

public final class Dtos {

    private Dtos() {}

    @Data
    public static class ChatRequest {

        @NotBlank(message = "Question must not be blank")
        @Size(max = 300, message = "Question must not exceed 300 characters")
        private String question;

        /** Optional — reserved for future category-scoped retrieval; not currently applied */
        @Size(max = 50, message = "Category must not exceed 50 characters")
        private String category;

        /** Optional — groups turns into a conversation for context tracking */
        @Size(max = 64, message = "Session ID must not exceed 64 characters")
        private String sessionId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {

        @NotBlank(message = "Username must not be blank")
        private String username;

        @NotBlank(message = "Password must not be blank")
        private String password;
    }

    @Data
    public static class DocumentUploadRequest {

        @NotBlank(message = "Category must not be blank")
        private String category;   // PROJECT_EXPERIENCE | STUDY_NOTES | CODE_SAMPLE

        @Size(max = 500, message = "Description must not exceed 500 characters")
        private String description;
    }

    @Data
    public static class UpdatePromptRequest {

        @NotBlank(message = "Content must not be blank")
        private String content;
    }
}
