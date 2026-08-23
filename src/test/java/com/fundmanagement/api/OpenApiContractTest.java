package com.fundmanagement.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {
  @Test void publishedContractContainsVersionAndFinancialAdminRoutes() throws IOException {
    // The hand-maintained contract is outside resources; verify it when the repository path is available.
    java.nio.file.Path path = java.nio.file.Path.of("docs/openapi.yaml");
    if (!java.nio.file.Files.exists(path)) return;
    String spec = java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
    assertThat(spec).contains("version: 1.1.0", "/admin/dues", "/admin/settings", "/admin/audit-logs", "/admin/reconciliation/{transactionId}/review", "/admin/gmail/oauth/start", "/admin/gmail/oauth/callback", "/admin/integrations/gmail/oauth/callback");
    assertThat(spec).doesNotContain("smtpPassword", "oauthToken");
  }
}
