package com.simon.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.rag.comm.constant.CacheConstant;
import com.simon.rag.dao.DocumentMapper;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.entity.Document;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final StringRedisTemplate redisTemplate;
    private final IngestionRunner ingestionRunner;   // separate bean → @Async works correctly

    @Value("${rag.upload.dir:/tmp/rag-uploads}")
    private String uploadDir;

    @PostConstruct
    void initUploadDir() throws IOException {
        Files.createDirectories(Path.of(uploadDir));
        log.info("Upload dir ready: {}", uploadDir);
    }

    @Override
    public Vos.DocumentResponse upload(MultipartFile file,
                                        Dtos.DocumentUploadRequest request,
                                        Long uploadedBy) {
        String fileName = file.getOriginalFilename();

        // --- Deduplication: skip if a non-failed record with same filename exists ---
        Document existing = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getFileName, fileName)
                        .ne(Document::getStatus, "FAILED")
                        .eq(Document::getDeleted, 0)
                        .last("LIMIT 1"));

        if (existing != null) {
            log.info("Duplicate upload skipped: fileName='{}', existingId={}", fileName, existing.getId());
            return Vos.DocumentResponse.builder()
                    .id(existing.getId())
                    .fileName(existing.getFileName())
                    .category(existing.getCategory())
                    .status(existing.getStatus())
                    .taskId(existing.getTaskId())
                    .chunkCount(existing.getChunkCount())
                    .createdAt(existing.getCreatedAt())
                    .message("File already exists — embedding skipped to avoid duplicate cost")
                    .build();
        }

        // --- Save file to disk first (decouples file lifetime from HTTP request) ---
        String taskId = UUID.randomUUID().toString();
        Path diskPath = Path.of(uploadDir, taskId);   // taskId = disk filename for recovery
        try {
            Files.write(diskPath, file.getBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save uploaded file to disk", e);
        }

        // --- Persist metadata to MySQL ---
        Document doc = new Document()
                .setFileName(fileName)
                .setFileType(file.getContentType())
                .setFileSize(file.getSize())
                .setCategory(request.getCategory())
                .setStatus("PENDING")
                .setTaskId(taskId)
                .setUploadedBy(uploadedBy);
        documentMapper.insert(doc);

        redisTemplate.opsForValue().set(
                CacheConstant.INGEST_TASK_PREFIX + taskId, "PENDING", 24, TimeUnit.HOURS);

        // --- Hand off to async runner (real @Async via separate bean proxy) ---
        ingestionRunner.ingest(doc.getId(), taskId, fileName, request.getCategory());

        return Vos.DocumentResponse.builder()
                .id(doc.getId())
                .fileName(doc.getFileName())
                .category(doc.getCategory())
                .status("PENDING")
                .taskId(taskId)
                .build();
    }

    @Override
    public Vos.IngestTaskResponse getTaskStatus(String taskId) {
        String status = redisTemplate.opsForValue().get(CacheConstant.INGEST_TASK_PREFIX + taskId);
        return Vos.IngestTaskResponse.builder()
                .taskId(taskId)
                .status(status != null ? status : "NOT_FOUND")
                .progress("COMPLETED".equals(status) ? 100 : "PROCESSING".equals(status) ? 50 : 0)
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
        // TODO: delete vectors from Qdrant by metadata filter (docId) in a future phase
        documentMapper.deleteById(documentId);
        log.info("Document {} deleted from MySQL — Qdrant vector cleanup is a future TODO", documentId);
    }
}
