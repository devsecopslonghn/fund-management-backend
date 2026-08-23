package com.fundmanagement.api.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-256-GCM for refresh tokens and short-lived PKCE verifiers. */
@Component
public class GmailTokenCrypto {
  private static final int IV_LENGTH = 12; private static final int TAG_BITS = 128; private final SecureRandom random = new SecureRandom();
  public String encrypt(String plaintext, String base64Key) { try { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); byte[] iv = new byte[IV_LENGTH]; random.nextBytes(iv); cipher.init(Cipher.ENCRYPT_MODE, key(base64Key), new GCMParameterSpec(TAG_BITS, iv)); byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)); return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array()); } catch (GeneralSecurityException | IllegalArgumentException ex) { throw new IllegalStateException("Gmail token encryption is unavailable", ex); } }
  public String decrypt(String encoded, String base64Key) { try { byte[] packed = Base64.getUrlDecoder().decode(encoded); if (packed.length <= IV_LENGTH) throw new IllegalArgumentException("Invalid encrypted value"); byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_LENGTH); byte[] ciphertext = java.util.Arrays.copyOfRange(packed, IV_LENGTH, packed.length); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(base64Key), new GCMParameterSpec(TAG_BITS, iv)); return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8); } catch (GeneralSecurityException | IllegalArgumentException ex) { throw new IllegalStateException("Gmail token decryption failed", ex); } }
  public String hash(String value) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); } }
  public byte[] keyBytes(String base64Key) { return key(base64Key).getEncoded(); }
  private SecretKeySpec key(String base64Key) { if (base64Key == null || base64Key.isBlank()) throw new IllegalArgumentException("Gmail token encryption key is not configured"); byte[] bytes; try { bytes = Base64.getDecoder().decode(base64Key); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Gmail token encryption key must be base64", ex); } if (bytes.length != 32) throw new IllegalArgumentException("Gmail token encryption key must be 32 bytes"); return new SecretKeySpec(bytes, "AES"); }
}
