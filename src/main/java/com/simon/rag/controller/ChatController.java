package com.simon.rag.controller;

import com.simon.rag.comm.result.Result;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat endpoint — the core RAG query interface.
 *
 * <p>POST /api/chat         — synchronous answer (for testing)
 * <p>POST /api/chat/stream  — SSE streaming answer (production UI)
 */
@Slf4j
@Tag(name = "Chat", description = "RAG-powered Q&A with personal knowledge base")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "Ask a question — synchronous response")
    @PostMapping
    public Result<Vos.ChatResponse> ask(
            @Valid @RequestBody Dtos.ChatRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        log.info("Chat request from [{}]: {}", userDetails.getUsername(), request.getQuestion());
        // TODO: resolve userId from userDetails in Phase 2
        Vos.ChatResponse response = chatService.ask(request, null);
        return Result.success(response);
    }

    @Operation(summary = "Ask a question — SSE streaming response")
    @PostMapping("/stream")
    public SseEmitter askStream(
            @Valid @RequestBody Dtos.ChatRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        log.info("Stream chat request from [{}]", userDetails.getUsername());
        return chatService.askStream(request, null);
    }
}