package com.simon.rag.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * System user — supports two roles: ADMIN and INTERVIEWER.
 *
 * <p>Interviewers are created by the admin with a shared or one-time token.
 * Admins are seeded via database init script.
 */
@Data
@Accessors(chain = true)
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Unique login username */
    @TableField("username")
    private String username;

    /** BCrypt-hashed password */
    @TableField("password")
    private String password;

    /** ROLE_ADMIN or ROLE_INTERVIEWER */
    @TableField("role")
    private String role;

    /** Display name shown in chat */
    @TableField("display_name")
    private String displayName;

    /** Whether the account is active */
    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** Logical delete flag (0=active, 1=deleted) */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}