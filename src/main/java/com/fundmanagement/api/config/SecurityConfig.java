package com.fundmanagement.api.config;

import java.util.List;
import java.util.ArrayList;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults()).httpBasic(Customizer.withDefaults())
        .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/public/**", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/api/v1/admin/gmail/oauth/callback").permitAll().requestMatchers("/api/v1/admin/settings/**", "/api/v1/admin/ingestion/**", "/api/v1/admin/migrations/**", "/api/v1/admin/gmail/**").hasRole("SUPER_ADMIN").requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN").anyRequest().denyAll()).build();
  }

  @Bean
  UserDetailsService users(@Value("${app.security.admin-username}") String username, @Value("${app.security.admin-password}") String password, @Value("${app.security.super-admin-username:}") String superUsername, @Value("${app.security.super-admin-password:}") String superPassword, PasswordEncoder encoder) {
    if (username.isBlank() || password.isBlank()) throw new IllegalStateException("APP_ADMIN_USERNAME and APP_ADMIN_PASSWORD must be configured");
    List<UserDetails> configured = new ArrayList<>(); configured.add(User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
    if (!superUsername.isBlank() && !superPassword.isBlank()) configured.add(User.withUsername(superUsername).password(encoder.encode(superPassword)).roles("SUPER_ADMIN", "ADMIN").build());
    return new InMemoryUserDetailsManager(configured);
  }
  @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }
  @Bean
  CorsConfigurationSource corsConfigurationSource(@Value("${app.security.allowed-origins}") List<String> origins) {
    CorsConfiguration configuration = new CorsConfiguration(); configuration.setAllowedOrigins(origins); configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")); configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key")); configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**", configuration); return source;
  }
}
