package com.simon.rag.controller;

import com.simon.rag.comm.result.Result;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Auth endpoint — login and token refresh.
 *
 * <p>POST /api/auth/login    — get JWT token
 * <p>POST /api/auth/logout   — blacklist current token in Redis
 * <p>POST /api/auth/refresh  — exchange near-expired token for a new one
 */
@Slf4j
@Tag(name = "Auth", description = "JWT authentication")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    // TODO: inject AuthService in Phase 3
    // private final AuthService authService;

    @Operation(summary = "Login and receive JWT token")
    @PostMapping("/login")
    public Result<Vos.LoginResponse> login(@Valid @RequestBody Dtos.LoginRequest request) {
        log.info("Login attempt for username: {}", request.getUsername());
        // TODO: implement in Phase 3 (JWT module)
        return Result.error("Auth service not yet implemented — coming in Phase 3");
    }

    @Operation(summary = "Logout — invalidate current JWT token")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authHeader) {
        // TODO: add token to Redis blacklist in Phase 3
        return Result.success();
    }
}