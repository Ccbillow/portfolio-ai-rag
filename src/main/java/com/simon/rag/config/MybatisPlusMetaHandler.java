package com.simon.rag.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Auto-fills createdAt and updatedAt fields on insert/update.
 * Eliminates manual timestamp setting across all entities.
 */
@Slf4j
@Component
public class MybatisPlusMetaHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("Auto-fill insert: createdAt, updatedAt");
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("Auto-fill update: updatedAt");
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}