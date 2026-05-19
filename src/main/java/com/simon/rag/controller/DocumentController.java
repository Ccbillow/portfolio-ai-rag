package com.simon.rag.controller;

import com.simon.rag.comm.result.Result;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.security.JwtUserDetails;
import com.simon.rag.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Document management — ROLE_ADMIN only.
 *
 * <p>POST   /api/documents/upload   — upload a knowledge document
 * <p>GET    /api/documents           — list all documents
 * <p>GET    /api/documents/task/{id} — poll ingestion progress
 * <p>DELETE /api/documents/{id}      — remove document + Qdrant chunks
 */
@Slf4j
@Tag(name = "Documents", description = "Knowledge base management (admin only)")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "Upload a knowledge document")
    @PostMapping("/upload")
    public Result<Vos.DocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            @Valid @ModelAttribute Dtos.DocumentUploadRequest request,
            @AuthenticationPrincipal JwtUserDetails userDetails) {

        Long uploadedBy = userDetails != null ? userDetails.getUserId() : null;
        log.info("Upload request: file={}, category={}, by={}",
                StringUtils.cleanPath(String.valueOf(file.getOriginalFilename())), request.getCategory(), uploadedBy);
        return Result.success(documentService.upload(file, request, uploadedBy));
    }

    @Operation(summary = "List documents (paginated)")
    @GetMapping
    public Result<Vos.PageResponse<Vos.DocumentResponse>> listAll(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(documentService.listAll(page, size));
    }

    @Operation(summary = "Poll async ingestion task progress")
    @GetMapping("/task/{taskId}")
    public Result<Vos.IngestTaskResponse> getTaskStatus(@PathVariable String taskId) {
        return Result.success(documentService.getTaskStatus(taskId));
    }

    @Operation(summary = "Delete a document and its Qdrant vector chunks")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.success();
    }

    @Operation(summary = "Retry a stuck PENDING/PROCESSING ingestion. Re-triggers if temp file exists; resets to FAILED otherwise so the file can be re-uploaded.")
    @PostMapping("/{id}/retry")
    public Result<Vos.DocumentResponse> retry(@PathVariable Long id) {
        return Result.success(documentService.retryIngestion(id));
    }
}
