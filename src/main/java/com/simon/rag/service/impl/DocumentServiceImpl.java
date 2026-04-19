package com.simon.rag.service.impl;

import com.simon.rag.comm.constant.CacheConstant;
import com.simon.rag.dao.DocumentMapper;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.entity.Document;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * DocumentServiceImpl — handles the ingestion lifecycle.
 *
 * <p>Upload flow:
 * <ol>
 *   <li>Validate file type and size</li>
 *   <li>Save document metadata to MySQL (status=PENDING)</li>
 *   <li>Return taskId immediately (non-blocking)</li>
 *   <li>@Async: Tika parse → chunk → embed → Qdrant store</li>
 *   <li>Update status to COMPLETED or FAILED</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final StringRedisTemplate redisTemplate;

    // TODO Phase 2: inject EmbeddingService and QdrantEmbeddingStore

    @Override
    public Vos.DocumentResponse upload(MultipartFile file,
                                        Dtos.DocumentUploadRequest request,
                                        Long uploadedBy) {
        String taskId = UUID.randomUUID().toString();

        // Persist metadata immediately
        Document doc = new Document()
                .setFileName(file.getOriginalFilename())
                .setFileType(file.getContentType())
                .setFileSize(file.getSize())
                .setCategory(request.getCategory())
                .setStatus("PENDING")
                .setTaskId(taskId)
                .setUploadedBy(uploadedBy);
        documentMapper.insert(doc);

        // Mark task in Redis so status endpoint can poll it
        redisTemplate.opsForValue().set(
                CacheConstant.INGEST_TASK_PREFIX + taskId,
                "PENDING",
                24, TimeUnit.HOURS
        );

        // Kick off async pipeline (non-blocking)
        processAsync(doc.getId(), file, taskId);

        return Vos.DocumentResponse.builder()
                .id(doc.getId())
                .fileName(doc.getFileName())
                .category(doc.getCategory())
                .status("PENDING")
                .taskId(taskId)
                .build();
    }

    @Async
    protected void processAsync(Long docId, MultipartFile file, String taskId) {
        // TODO Phase 2 — Tika parse → chunk → embed → Qdrant
        log.info("Async ingestion started for docId={}, taskId={}", docId, taskId);
        redisTemplate.opsForValue().set(
                CacheConstant.INGEST_TASK_PREFIX + taskId, "PROCESSING");
        // Simulated delay for now
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        redisTemplate.opsForValue().set(
                CacheConstant.INGEST_TASK_PREFIX + taskId, "COMPLETED");
        documentMapper.updateIngestionResult(docId, "COMPLETED", 0, null);
        log.info("Async ingestion stub completed for docId={}", docId);
    }

    @Override
    public Vos.IngestTaskResponse getTaskStatus(String taskId) {
        String status = redisTemplate.opsForValue()
                .get(CacheConstant.INGEST_TASK_PREFIX + taskId);
        return Vos.IngestTaskResponse.builder()
                .taskId(taskId)
                .status(status != null ? status : "NOT_FOUND")
                .progress("COMPLETED".equals(status) ? 100 : 50)
                .build();
    }

    @Override
    public List<Vos.DocumentResponse> listAll() {
        return documentMapper.selectList(null).stream()
                .map(doc -> Vos.DocumentResponse.builder()
                        .id(doc.getId())
                        .fileName(doc.getFileName())
                        .fileType(doc.getFileType())
                        .category(doc.getCategory())
                        .chunkCount(doc.getChunkCount())
                        .status(doc.getStatus())
                        .taskId(doc.getTaskId())
                        .createdAt(doc.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void delete(Long documentId) {
        // TODO Phase 2: also delete vectors from Qdrant by metadata filter
        documentMapper.deleteById(documentId);
        log.info("Document {} deleted from MySQL — Qdrant cleanup coming in Phase 2", documentId);
    }
}