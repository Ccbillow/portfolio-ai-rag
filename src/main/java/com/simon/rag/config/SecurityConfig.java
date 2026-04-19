package com.simon.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 1 security config — opens all endpoints so we can test
 * the API without auth tokens during local development.
 *
 * <p>IMPORTANT: This is replaced in Phase 3 with full JWT + RBAC.
 * DO NOT deploy this to production.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Swagger UI
                .requestMatchers(
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/doc.html",
                    "/webjars/**"
                ).permitAll()
                // Health check
                .requestMatchers("/actuator/health").permitAll()
                // TODO Phase 3: tighten these — require JWT for all API calls
                .anyRequest().permitAll()
            );
        return http.build();
    }
}