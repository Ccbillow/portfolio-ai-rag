package com.simon.rag.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Records every question/answer pair for audit and analytics.
 *
 * <p>Storing chat history lets the admin review what interviewers asked,
 * identify gaps in the knowledge base, and improve chunking strategy.
 */
@Data
@Accessors(chain = true)
@TableName("rag_chat_history")
public class ChatHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Session ID — groups messages from a single browser session */
    @TableField("session_id")
    private String sessionId;

    /** The user who asked (null if anonymous) */
    @TableField("user_id")
    private Long userId;

    /** Raw question text */
    @TableField("question")
    private String question;

    /** Full AI answer text */
    @TableField("answer")
    private String answer;

    /** Comma-separated source document IDs used for this answer */
    @TableField("source_doc_ids")
    private String sourceDocIds;

    /** Qdrant similarity scores for top retrieved chunks (JSON array) */
    @TableField("retrieval_scores")
    private String retrievalScores;

    /** LLM model used: gpt-3.5-turbo / ollama/llama3.2 */
    @TableField("model_used")
    private String modelUsed;

    /** Time taken to generate the answer in milliseconds */
    @TableField("latency_ms")
    private Long latencyMs;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}