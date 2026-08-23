package com.fundmanagement.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Lightweight per-instance limit; production replicas should enforce an edge/gateway limit too. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().matches(".*/api/v1/(public|me)/.*"); }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String key = request.getRemoteAddr(); Window window = windows.computeIfAbsent(key, ignored -> new Window());
    synchronized (window) { long now = Instant.now().getEpochSecond(); if (now - window.startedAt >= 60) { window.startedAt = now; window.count = 0; } if (++window.count > 60) { response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Too many requests"); return; } }
    chain.doFilter(request, response);
  }
  private static final class Window { long startedAt = Instant.now().getEpochSecond(); int count; }
}
