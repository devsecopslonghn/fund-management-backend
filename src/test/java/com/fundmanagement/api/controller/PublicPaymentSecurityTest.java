package com.fundmanagement.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fundmanagement.api.config.SecurityConfig;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.service.DashboardService;
import com.fundmanagement.api.service.DuesService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = PublicController.class, properties = {"app.security.admin-username=test-admin", "app.security.admin-password=test-only-password"})
@Import(SecurityConfig.class)
class PublicPaymentSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean DashboardService dashboard;
  @MockitoBean DuesService dues;

  @Test void anonymousPayerCanLookupByNameWithoutAuthentication() throws Exception {
    when(dues.publicPaymentOptions(any())).thenReturn(new ApiDtos.PublicPaymentOptionsResponse(false, List.of(new ApiDtos.PublicMemberMatch("Nguyễn Văn A", null)), List.of(new ApiDtos.PublicDuePeriodResponse("due-1", "2026-08", 100_000))));
    mvc.perform(post("/api/v1/public/payment-options").contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Nguyễn Văn A\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.duePeriods[0].outstandingAmount").value(100_000));
  }

  @Test void anonymousPayerCanCreateIntentWithoutAuthentication() throws Exception {
    when(dues.createPublicIntent(any())).thenReturn(new ApiDtos.PublicPaymentIntentResponse("intent-1", 100_000, "QUY A001 082026", "https://qr.invalid/image", "QUY CHUNG", "123456789", "Test Bank", Instant.parse("2026-08-24T01:00:00Z"), IntentStatus.PENDING));
    mvc.perform(post("/api/v1/public/payment-intents").contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Nguyễn Văn A\",\"duePeriodIds\":[\"due-1\"]}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(100_000)).andExpect(jsonPath("$.accountNumber").value("123456789"));
  }
}
