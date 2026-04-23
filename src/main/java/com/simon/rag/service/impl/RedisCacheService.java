package com.simon.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.comm.constant.CacheConstant;
import com.simon.rag.config.RagProperties;
import com.simon.rag.domain.vo.Vos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    // ----------------------------------------------------------------
    //  Chat answer cache
    // ----------------------------------------------------------------

    public Vos.ChatResponse getChatCache(String key, long start) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                log.info("Cache hit: {}", key);
                Vos.ChatResponse cached = objectMapper.readValue(json, Vos.ChatResponse.class);
                return Vos.ChatResponse.builder()
                        .answer(cached.getAnswer())
                        .sources(cached.getSources())
                        .modelUsed(cached.getModelUsed())
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            }
        } catch (Exception e) {
            log.warn("Cache read failed: {}", e.getMessage());
        }
        return null;
    }

    public void putChatCache(String key, Vos.ChatResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    key, objectMapper.writeValueAsString(response),
                    ragProperties.getCache().getTtlHours(), TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Cache write failed: {}", e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  Ingestion task status
    // ----------------------------------------------------------------

    public void setIngestionStatus(String taskId, String status) {
        try {
            redisTemplate.opsForValue().set(CacheConstant.INGEST_TASK_PREFIX + taskId, status);
        } catch (Exception e) {
            log.warn("Ingestion status write failed: taskId={}, status={}, err={}", taskId, status, e.getMessage());
        }
    }

    public void setIngestionStatus(String taskId, String status, long ttlHours) {
        try {
            redisTemplate.opsForValue().set(
                    CacheConstant.INGEST_TASK_PREFIX + taskId, status, ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Ingestion status write failed: taskId={}, status={}, err={}", taskId, status, e.getMessage());
        }
    }

    public String getIngestionStatus(String taskId) {
        try {
            return redisTemplate.opsForValue().get(CacheConstant.INGEST_TASK_PREFIX + taskId);
        } catch (Exception e) {
            log.warn("Ingestion status read failed: taskId={}, err={}", taskId, e.getMessage());
            return null;
        }
    }
}
