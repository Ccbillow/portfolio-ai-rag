package com.simon.rag.service;

import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Document service — manages knowledge base ingestion lifecycle.
 */
public interface DocumentService {

    /**
     * Accepts an uploaded file, persists metadata, and kicks off async ingestion.
     * Returns immediately with a taskId for progress tracking.
     */
    Vos.DocumentResponse upload(MultipartFile file,
                                 Dtos.DocumentUploadRequest request,
                                 Long uploadedBy);

    /** Poll ingestion progress by taskId */
    Vos.IngestTaskResponse getTaskStatus(String taskId);

    /** List all documents (admin view) */
    List<Vos.DocumentResponse> listAll();

    /** Delete a document and remove its chunks from Qdrant */
    void delete(Long documentId);
}