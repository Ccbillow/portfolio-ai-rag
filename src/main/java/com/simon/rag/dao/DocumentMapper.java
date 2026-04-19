package com.simon.rag.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.rag.domain.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Document mapper.
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * Update ingestion status and chunk count atomically.
     * Called from the async ingestion service on completion/failure.
     */
    @Update("UPDATE rag_document SET status = #{status}, chunk_count = #{chunkCount}, " +
            "error_message = #{errorMessage}, updated_at = NOW() WHERE id = #{id}")
    int updateIngestionResult(@Param("id") Long id,
                               @Param("status") String status,
                               @Param("chunkCount") Integer chunkCount,
                               @Param("errorMessage") String errorMessage);
}