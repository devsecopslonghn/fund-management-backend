package com.fundmanagement.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final SecureRandom random = new SecureRandom();
  public String newRawToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  public String hash(String rawToken) {
    try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("Required cryptographic algorithm unavailable", ex); }
  }
  public String personalLink(String rawToken) { return "/me/" + rawToken; }
}
