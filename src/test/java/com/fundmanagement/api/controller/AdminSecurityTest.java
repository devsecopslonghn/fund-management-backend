package com.fundmanagement.api.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fundmanagement.api.config.SecurityConfig;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.DashboardService;
import com.fundmanagement.api.service.DuesService;
import com.fundmanagement.api.service.ExpenseService;
import com.fundmanagement.api.service.MemberService;
import com.fundmanagement.api.service.PaymentService;
import com.fundmanagement.api.service.SettingsService;
import com.fundmanagement.api.service.AuditService;
import com.fundmanagement.api.service.ReportService;
import com.fundmanagement.api.service.IngestionService;
import com.fundmanagement.api.service.ReconciliationService;
import com.fundmanagement.api.service.LegacyMigrationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = AdminController.class, properties = {"app.security.admin-username=test-admin", "app.security.admin-password=test-only-password"})
@Import(SecurityConfig.class)
class AdminSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean MemberService members;
  @MockitoBean DuesService dues;
  @MockitoBean PaymentService payments;
  @MockitoBean ExpenseService expenses;
  @MockitoBean DashboardService dashboard;
  @MockitoBean SettingsService settings;
  @MockitoBean AuditService audit;
  @MockitoBean ReportService reports;
  @MockitoBean IngestionService ingestion;
  @MockitoBean ReconciliationService reconciliation;
  @MockitoBean LegacyMigrationService migration;

  @Test void rejectsUnauthenticatedAdminRequest() throws Exception { mvc.perform(get("/api/v1/admin/members")).andExpect(status().isUnauthorized()); }
  @Test @WithMockUser(roles = "ADMIN") void allowsAdminRequest() throws Exception { when(members.list(0, 25)).thenReturn(new ApiDtos.MemberPageResponse(List.of(), 0, 25, 0)); mvc.perform(get("/api/v1/admin/members")).andExpect(status().isOk()); }
}
