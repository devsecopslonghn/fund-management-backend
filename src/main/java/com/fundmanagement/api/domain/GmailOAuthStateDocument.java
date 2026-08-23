package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("gmail_oauth_states")
public class GmailOAuthStateDocument {
  @Id public String id;
  @Indexed(unique = true) public String stateHash;
  /** Encrypted PKCE verifier; plaintext state/verifier never persist. */
  public String encryptedCodeVerifier;
  public String actor;
  @Indexed public Instant expiresAt;
  public boolean used;
  public Instant usedAt;
  public Instant createdAt;
}
