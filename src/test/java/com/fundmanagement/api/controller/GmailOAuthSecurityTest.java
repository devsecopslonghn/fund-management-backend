package com.fundmanagement.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.config.SecurityConfig;
import com.fundmanagement.api.service.GmailOAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.URI;

@WebMvcTest(value = {GmailOAuthController.class, GmailOAuthLegacyCallbackController.class}, properties = {"app.security.admin-username=test-admin", "app.security.admin-password=test-only-password"})
@Import(SecurityConfig.class)
class GmailOAuthSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean GmailOAuthService oauth;
  @BeforeEach void callbackStub() { when(oauth.callback(nullable(String.class), nullable(String.class), nullable(String.class))).thenReturn(new com.fundmanagement.api.dto.ApiDtos.GmailOAuthCallbackResponse(true, "ok")); when(oauth.frontendCallbackRedirect(anyBoolean())).thenReturn(URI.create("https://frontend.example.test/?gmailOAuth=connected")); }
  @Test void callbackIsReachableWithoutBasicAuthForBrowserRedirect() throws Exception { mvc.perform(get("/api/v1/admin/gmail/oauth/callback").param("error", "access_denied")).andExpect(status().isFound()).andExpect(header().string("Location", "https://frontend.example.test/?gmailOAuth=connected")); }
  @Test void registeredGoogleRedirectAliasIsAlsoPublicAndRedirects() throws Exception { mvc.perform(get("/api/v1/admin/integrations/gmail/oauth/callback").param("state", "state").param("code", "code")).andExpect(status().isFound()).andExpect(header().string("Location", "https://frontend.example.test/?gmailOAuth=connected")); }
  @Test void startRequiresAuthentication() throws Exception { mvc.perform(post("/api/v1/admin/gmail/oauth/start")).andExpect(status().isUnauthorized()); }
  @Test @WithMockUser(roles = "ADMIN") void startRequiresSuperAdmin() throws Exception { mvc.perform(post("/api/v1/admin/gmail/oauth/start")).andExpect(status().isForbidden()); }
  @Test @WithMockUser(roles = "SUPER_ADMIN") void superAdminMayReadStatus() throws Exception { mvc.perform(get("/api/v1/admin/gmail/oauth/status")).andExpect(status().isOk()); }
}
