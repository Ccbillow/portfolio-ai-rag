package com.simon.rag.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Represents an uploaded knowledge document.
 *
 * <p>After ingestion, the document is chunked and each chunk's embedding
 * is stored in Qdrant. This table tracks the document metadata and status.
 */
@Data
@Accessors(chain = true)
@TableName("rag_document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Original filename */
    @TableField("file_name")
    private String fileName;

    /** MIME type: application/pdf, text/markdown, text/plain, etc. */
    @TableField("file_type")
    private String fileType;

    /** File size in bytes */
    @TableField("file_size")
    private Long fileSize;

    /**
     * Knowledge category — used for Metadata Filtering in Qdrant.
     * Values: PROJECT_EXPERIENCE | STUDY_NOTES | CODE_SAMPLE
     */
    @TableField("category")
    private String category;

    /** Number of chunks produced after splitting */
    @TableField("chunk_count")
    private Integer chunkCount;

    /**
     * Ingestion status.
     * PENDING → PROCESSING → COMPLETED | FAILED
     */
    @TableField("status")
    private String status;

    /** Error message if status = FAILED */
    @TableField("error_message")
    private String errorMessage;

    /** Async task ID for progress tracking via SSE */
    @TableField("task_id")
    private String taskId;

    /** Who uploaded this document */
    @TableField("uploaded_by")
    private Long uploadedBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}