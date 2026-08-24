package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.PaymentReferenceDocument;
import com.fundmanagement.api.repository.PaymentReferenceRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Creates stable, opaque ten-character references without exposing member identity. */
@Service
public class PaymentReferenceService {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int CODE_LENGTH = 10;
  private static final int MAX_ATTEMPTS = 12;
  private final PaymentReferenceRepository references;
  private final SecureRandom random = new SecureRandom();

  public PaymentReferenceService(PaymentReferenceRepository references) { this.references = references; }

  public String referenceFor(String memberId, String month) {
    if (memberId == null || memberId.isBlank() || month == null || month.isBlank()) throw new IllegalArgumentException("Payment reference scope is invalid");
    var existing = references.findByMemberIdAndMonth(memberId, month);
    if (existing.isPresent()) return existing.get().code;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      PaymentReferenceDocument candidate = new PaymentReferenceDocument(); candidate.id = UUID.randomUUID().toString(); candidate.memberId = memberId; candidate.month = month; candidate.code = randomCode(); candidate.createdAt = Instant.now();
      try { return references.insert(candidate).code; }
      catch (DuplicateKeyException race) {
        var concurrent = references.findByMemberIdAndMonth(memberId, month);
        if (concurrent.isPresent()) return concurrent.get().code;
      }
    }
    throw new IllegalStateException("Could not allocate a payment reference");
  }

  private String randomCode() {
    StringBuilder code = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    return code.toString();
  }
}
