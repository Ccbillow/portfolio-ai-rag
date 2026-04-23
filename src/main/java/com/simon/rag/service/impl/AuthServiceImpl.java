package com.simon.rag.service.impl;

import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.security.JwtUserDetails;
import com.simon.rag.security.JwtUtil;
import com.simon.rag.security.UserDetailsServiceImpl;
import com.simon.rag.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public Vos.LoginResponse login(Dtos.LoginRequest request) {
        JwtUserDetails userDetails =
                (JwtUserDetails) userDetailsService.loadUserByUsername(request.getUsername());

        if (!userDetails.isEnabled()) {
            throw new BadCredentialsException("Account is disabled");
        }
        if (!passwordEncoder.matches(request.getPassword(), userDetails.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        String token = jwtUtil.generateToken(userDetails.getUsername(), userDetails.getRole());
        log.info("Login successful: user={}, role={}", userDetails.getUsername(), userDetails.getRole());

        return Vos.LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .username(userDetails.getUsername())
                .role(userDetails.getRole())
                .expiresInSeconds(jwtUtil.getExpirationMs() / 1000)
                .build();
    }
}
