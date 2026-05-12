package com.simon.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.rag.comm.enums.IngestionStatus;
import com.simon.rag.config.RagProperties;
import com.simon.rag.dao.DocumentMapper;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.entity.Document;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.DocumentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final RedisCacheService redisCacheService;
    private final IngestionRunner ingestionRunner;
    private final QdrantSearchService qdrantSearchService;
    private final RagProperties ragProperties;

    @PostConstruct
    void initUploadDir() throws IOException {
        Files.createDirectories(Path.of(ragProperties.getUpload().getDir()));
        log.info("Upload dir ready: {}", ragProperties.getUpload().getDir());
    }

    @Override
    public Vos.DocumentResponse upload(MultipartFile file,
                                        Dtos.DocumentUploadRequest request,
                                        Long uploadedBy) {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new RuntimeException("Uploaded file has no filename");
        }

        // --- Deduplication: skip if a non-failed record with same filename exists ---
        Document existing = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getFileName, fileName)
                        .ne(Document::getStatus, IngestionStatus.FAILED.name())
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
        Path diskPath = Path.of(ragProperties.getUpload().getDir(), taskId);
        try (java.io.InputStream in = file.getInputStream()) {
            Files.copy(in, diskPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save uploaded file to disk", e);
        }

        // --- Persist metadata to MySQL — clean up temp file if this fails ---
        Document doc = new Document()
                .setFileName(fileName)
                .setFileType(file.getContentType())
                .setFileSize(file.getSize())
                .setCategory(request.getCategory())
                .setStatus(IngestionStatus.PENDING.name())
                .setTaskId(taskId)
                .setUploadedBy(uploadedBy);
        try {
            documentMapper.insert(doc);
        } catch (Exception e) {
            try { Files.deleteIfExists(diskPath); } catch (IOException ignored) {}
            throw new RuntimeException("Failed to persist document metadata", e);
        }

        redisCacheService.setIngestionStatus(taskId, IngestionStatus.PENDING.name(), 24);

        ingestionRunner.ingest(doc.getId(), taskId, fileName, request.getCategory());

        return Vos.DocumentResponse.builder()
                .id(doc.getId())
                .fileName(doc.getFileName())
                .category(doc.getCategory())
                .status(IngestionStatus.PENDING.name())
                .taskId(taskId)
                .build();
    }

    @Override
    public Vos.IngestTaskResponse getTaskStatus(String taskId) {
        // Primary source: Redis (fast)
        String status = redisCacheService.getIngestionStatus(taskId);

        // Fallback: MySQL — covers Redis restart / TTL expiry
        if (status == null) {
            Document doc = documentMapper.selectOne(
                    new LambdaQueryWrapper<Document>()
                            .eq(Document::getTaskId, taskId)
                            .last("LIMIT 1"));
            status = doc != null ? doc.getStatus() : null;
        }

        return Vos.IngestTaskResponse.builder()
                .taskId(taskId)
                .status(status != null ? status : "NOT_FOUND")
                .progress(IngestionStatus.COMPLETED.name().equals(status) ? 100
                        : IngestionStatus.PROCESSING.name().equals(status) ? 50 : 0)
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
        // Delete from Qdrant first. If it fails, abort — do NOT remove the MySQL record.
        // Keeping the MySQL record prevents re-upload from creating duplicate ghost chunks in Qdrant.
        qdrantSearchService.deleteByDocId(String.valueOf(documentId));
        documentMapper.deleteById(documentId);
        log.info("Document {} deleted from Qdrant and MySQL", documentId);
    }
}
