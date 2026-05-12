package com.simon.rag.comm.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.comm.result.Result;
import com.simon.rag.comm.result.ResultCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiter for chat endpoints using Bucket4j in-process token buckets.
 * Limits: 5 requests/minute and 20 requests/day per IP.
 * Minute limit is checked first to avoid burning daily quota on rapid-fire bursts.
 *
 * Buckets are cleared on a schedule to prevent unbounded map growth:
 *   - minuteBuckets every 10 min (buckets refill every 1 min anyway)
 *   - dailyBuckets once per day (midnight UTC)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int  MINUTE_CAPACITY = 5;
    private static final int  DAILY_CAPACITY  = 20;

    @Value("${rag.rate-limit.enabled:true}")
    private boolean enabled;

    private final ConcurrentHashMap<String, Bucket> minuteBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> dailyBuckets  = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void evictMinuteBuckets() {
        int size = minuteBuckets.size();
        minuteBuckets.clear();
        if (size > 0) log.debug("Evicted {} minute-limit buckets", size);
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void evictDailyBuckets() {
        int size = dailyBuckets.size();
        dailyBuckets.clear();
        log.info("Evicted {} daily-limit buckets (midnight reset)", size);
    }

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler) throws Exception {

        if (!enabled) return true;

        String ip = resolveClientIp(request);

        // Check per-minute limit first — keeps daily quota safe from burst spamming
        Bucket minute = minuteBuckets.computeIfAbsent(ip,
                k -> newBucket(MINUTE_CAPACITY, Duration.ofMinutes(1)));
        if (!minute.tryConsume(1)) {
            log.warn("Per-minute rate limit exceeded: ip={}, uri={}", ip, request.getRequestURI());
            writeError(response, ResultCode.RATE_LIMITED);
            return false;
        }

        // Then check daily limit
        Bucket daily = dailyBuckets.computeIfAbsent(ip,
                k -> newBucket(DAILY_CAPACITY, Duration.ofDays(1)));
        if (!daily.tryConsume(1)) {
            log.warn("Daily rate limit exceeded: ip={}, uri={}", ip, request.getRequestURI());
            writeError(response, ResultCode.DAILY_RATE_LIMITED);
            return false;
        }

        return true;
    }

    private Bucket newBucket(int capacity, Duration period) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, period)
                        .build())
                .build();
    }

    private void writeError(HttpServletResponse response, ResultCode code) throws Exception {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(Result.error(code)));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Use the rightmost IP — it is appended by the nearest trusted proxy (Nginx),
            // while the leftmost is the client-supplied value and trivially spoofable.
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
