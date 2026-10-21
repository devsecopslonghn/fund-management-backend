package com.fundmanagement.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fundmanagement.api.config.SecurityConfig;
import com.fundmanagement.api.dto.MemberDuesRepairDtos;
import com.fundmanagement.api.service.MemberDuesRepairService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = MemberDuesRepairController.class, properties = {
    "app.security.admin-username=test-admin", "app.security.admin-password=test-only-password"})
@Import(SecurityConfig.class)
class MemberDuesRepairSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean MemberDuesRepairService repairs;
  private static final String PATH = "/api/v1/admin/migrations/member-dues/repair";
  private static final String REQUEST = """
      {"memberCode":"ANPH44225","year":2026,"reason":"Repair missing August period"}
      """;

  @Test void anonymousCannotPreviewOrApply() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isUnauthorized());
    verifyNoInteractions(repairs);
  }
  @Test @WithMockUser(roles = "ADMIN") void ordinaryAdminCannotRepair() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
    verifyNoInteractions(repairs);
  }
  @Test @WithMockUser(username = "operator", roles = "SUPER_ADMIN") void superAdminUsesPreviewByDefault() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isOk());
    verify(repairs).repair(any(MemberDuesRepairDtos.Request.class), eq("operator"));
  }
  @Test @WithMockUser(roles = "SUPER_ADMIN") void rejectsInvalidYearAndReason() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
        .content("{\"memberCode\":\"ANPH44225\",\"year\":0,\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repairs);
  }
}
