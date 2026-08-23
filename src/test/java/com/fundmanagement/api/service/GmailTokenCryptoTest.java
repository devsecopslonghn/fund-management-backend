package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GmailTokenCryptoTest {
  private final GmailTokenCrypto crypto = new GmailTokenCrypto();
  @Test void encryptsAndDecryptsRefreshTokenWithAead() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); String encoded = Base64.getEncoder().encodeToString(key); String ciphertext = crypto.encrypt("refresh-token-fixture", encoded); assertThat(ciphertext).doesNotContain("refresh-token-fixture"); assertThat(crypto.decrypt(ciphertext, encoded)).isEqualTo("refresh-token-fixture"); }
  @Test void rejectsWeakKey() { assertThatThrownBy(() -> crypto.encrypt("token", Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class); }
}
