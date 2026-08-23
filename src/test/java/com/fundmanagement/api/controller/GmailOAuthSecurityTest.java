package com.fundmanagement.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fundmanagement.api.config.SecurityConfig;
import com.fundmanagement.api.service.GmailOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = GmailOAuthController.class, properties = {"app.security.admin-username=test-admin", "app.security.admin-password=test-only-password"})
@Import(SecurityConfig.class)
class GmailOAuthSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean GmailOAuthService oauth;
  @Test void callbackIsReachableWithoutBasicAuthForBrowserRedirect() throws Exception { mvc.perform(get("/api/v1/admin/gmail/oauth/callback").param("error", "access_denied")).andExpect(status().isOk()); }
  @Test void startRequiresAuthentication() throws Exception { mvc.perform(post("/api/v1/admin/gmail/oauth/start")).andExpect(status().isUnauthorized()); }
  @Test @WithMockUser(roles = "ADMIN") void startRequiresSuperAdmin() throws Exception { mvc.perform(post("/api/v1/admin/gmail/oauth/start")).andExpect(status().isForbidden()); }
  @Test @WithMockUser(roles = "SUPER_ADMIN") void superAdminMayReadStatus() throws Exception { mvc.perform(get("/api/v1/admin/gmail/oauth/status")).andExpect(status().isOk()); }
}
