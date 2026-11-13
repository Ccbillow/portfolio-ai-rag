package com.simon.rag.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/** One answered question. {@code created_at} is filled by the database default. */
@Data
@Accessors(chain = true)
@TableName("rag_chat_history")
public class ChatHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("question")
    private String question;

    @TableField("answer")
    private String answer;

    /** Comma-separated distinct document IDs of the chunks sent to the LLM. */
    @TableField("source_doc_ids")
    private String sourceDocIds;

    /** JSON array of rerank scores, in context order. */
    @TableField("retrieval_scores")
    private String retrievalScores;

    /** LLM model id, or "rule-based" for answers produced without the LLM. */
    @TableField("model_used")
    private String modelUsed;

    @TableField("latency_ms")
    private Long latencyMs;
}
