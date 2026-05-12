package com.simon.rag.service.impl;

import com.simon.rag.comm.exception.BusinessException;
import com.simon.rag.dao.PromptTemplateMapper;
import com.simon.rag.domain.entity.PromptTemplate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private final PromptTemplateMapper mapper;

    // Volatile reference: loadAll() swaps the whole map atomically, eliminating the
    // clear-then-repopulate window that would cause concurrent get() to throw.
    private volatile Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadAll() {
        List<PromptTemplate> templates = mapper.selectList(null);
        ConcurrentHashMap<String, String> fresh = new ConcurrentHashMap<>();
        templates.forEach(t -> fresh.put(t.getName(), t.getContent()));
        cache = fresh;
        log.info("Loaded {} prompt templates from DB: {}", templates.size(), fresh.keySet());
    }

    public String get(String name) {
        String value = cache.get(name);
        if (value == null) {
            throw new BusinessException("Prompt template not found: " + name);
        }
        return value;
    }

    public PromptTemplate update(String name, String content) {
        int rows = mapper.updateContentByName(name, content);
        if (rows == 0) {
            throw new BusinessException("Prompt template not found: " + name);
        }
        cache.put(name, content);
        log.info("Updated prompt template '{}' in DB and cache", name);
        return mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getName, name));
    }

    public void reload() {
        loadAll();
        log.info("Reloaded all prompt templates from DB");
    }

    public List<PromptTemplate> listAll() {
        return mapper.selectList(null);
    }
}
