package com.simon.rag.service.ingestion;

import com.simon.rag.comm.exception.BusinessException;
import com.simon.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single owner of the upload temp-file layout ({@code <upload.dir>/<taskId>}).
 * Previously the path derivation was duplicated across DocumentServiceImpl
 * (upload + retry) and IngestionRunner.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadStorage {

    private final RagProperties ragProperties;

    @PostConstruct
    void initUploadDir() throws IOException {
        Files.createDirectories(Path.of(ragProperties.getUpload().getDir()));
        log.info("Upload dir ready: {}", ragProperties.getUpload().getDir());
    }

    public Path pathOf(String taskId) {
        return Path.of(ragProperties.getUpload().getDir(), taskId);
    }

    /** Streams the uploaded file to disk (decouples file lifetime from the HTTP request). */
    public void save(MultipartFile file, String taskId) {
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, pathOf(taskId));
        } catch (IOException e) {
            throw new BusinessException("Failed to save uploaded file to disk", e);
        }
    }

    public boolean exists(String taskId) {
        return Files.exists(pathOf(taskId));
    }

    /** Best-effort delete — never throws (used in cleanup paths). */
    public void delete(String taskId) {
        try {
            Files.deleteIfExists(pathOf(taskId));
        } catch (Exception e) {
            log.warn("Failed to delete temp file {}: {}", pathOf(taskId), e.getMessage());
        }
    }
}
