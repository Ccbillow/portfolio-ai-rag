package com.simon.rag.controller;

import com.simon.rag.comm.result.Result;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.entity.PromptTemplate;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.impl.PromptTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin management — ROLE_ADMIN only.
 *
 * <p>GET  /api/admin/prompts        — list all prompt templates
 * <p>PUT  /api/admin/prompts/{name} — update a template's content (hot-reload)
 * <p>POST /api/admin/prompts/reload — force reload all templates from DB
 */
@Slf4j
@Tag(name = "Admin", description = "Admin management (admin only)")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PromptTemplateService promptTemplateService;

    @Operation(summary = "List all prompt templates")
    @GetMapping("/prompts")
    public Result<List<Vos.PromptTemplateVo>> listPrompts() {
        List<Vos.PromptTemplateVo> vos = promptTemplateService.listAll().stream()
                .map(this::toVo)
                .toList();
        return Result.success(vos);
    }

    @Operation(summary = "Update a prompt template content (takes effect immediately, no restart needed)")
    @PutMapping("/prompts/{name}")
    public Result<Vos.PromptTemplateVo> updatePrompt(
            @PathVariable String name,
            @Valid @RequestBody Dtos.UpdatePromptRequest request) {
        log.info("Admin updating prompt template: {}", name);
        promptTemplateService.update(name, request.getContent());
        PromptTemplate updated = promptTemplateService.listAll().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseThrow();
        return Result.success(toVo(updated));
    }

    @Operation(summary = "Force reload all prompt templates from DB into memory cache")
    @PostMapping("/prompts/reload")
    public Result<String> reloadPrompts() {
        promptTemplateService.reload();
        return Result.success("Reloaded " + promptTemplateService.listAll().size() + " prompt templates");
    }

    private Vos.PromptTemplateVo toVo(PromptTemplate t) {
        return Vos.PromptTemplateVo.builder()
                .id(t.getId())
                .name(t.getName())
                .content(t.getContent())
                .description(t.getDescription())
                .version(t.getVersion())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
