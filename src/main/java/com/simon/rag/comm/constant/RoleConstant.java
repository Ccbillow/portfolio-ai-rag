package com.simon.rag.comm.constant;

/**
 * Role constants for RBAC.
 *
 * <p>ADMIN   — can upload documents, manage knowledge base, view all chat history
 * <p>INTERVIEWER — can chat with the AI assistant (read-only)
 */
public final class RoleConstant {

    private RoleConstant() {}

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String INTERVIEWER = "ROLE_INTERVIEWER";

    /** Spring Security prefix-stripped versions (for @PreAuthorize) */
    public static final String ADMIN_AUTH = "ADMIN";
    public static final String INTERVIEWER_AUTH = "INTERVIEWER";
}