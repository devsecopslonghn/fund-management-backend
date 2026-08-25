package com.fundmanagement.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

  @Test void publicPaymentDashboardReturnsMemberDuesButNoContactOrBankMessageData() throws Exception {
    ApiDtos.PublicPaymentDashboardDue due = new ApiDtos.PublicPaymentDashboardDue("due-1", "2026-08", 100_000, 20_000, 80_000, com.fundmanagement.api.domain.DueStatus.PARTIAL);
    ApiDtos.PublicPaymentDashboardMember member = new ApiDtos.PublicPaymentDashboardMember("member-1", "Nguyễn Văn A", "A001", "2026-06", "2026-07", 100_000, "SOME_MONTHS_MISSING", List.of(due));
    when(dashboard.publicPaymentDashboard()).thenReturn(new ApiDtos.PublicPaymentDashboardResponse(1_000_000, 100_000, 20_000, List.of(), List.of(member)));
    mvc.perform(get("/api/v1/public/payment-dashboard"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members[0].memberCode").value("A001"))
        .andExpect(jsonPath("$.members[0].duePeriods[0].outstandingAmount").value(80_000))
        .andExpect(jsonPath("$.members[0].email").doesNotExist())
        .andExpect(jsonPath("$.members[0].duePeriods[0].rawPayload").doesNotExist());
  }

  @Test void anonymousMemberHistoryReturnsOnlySafePeriodAggregates() throws Exception {
    ApiDtos.PublicPaymentHistoryResponse history = new ApiDtos.PublicPaymentHistoryResponse(
        "A001", "Nguyễn Văn A", 2026, 200_000, 100_000, 100_000,
        List.of(new ApiDtos.PublicPaymentHistoryEntry("due-1", "2026-08", 100_000, 100_000, 0, com.fundmanagement.api.domain.DueStatus.PAID)));
    when(dues.publicPaymentHistory("A001", 2026)).thenReturn(history);
    mvc.perform(get("/api/v1/public/payment-history").param("memberCode", "A001").param("year", "2026"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memberCode").value("A001"))
        .andExpect(jsonPath("$.periods[0].amountPaid").value(100_000))
        .andExpect(jsonPath("$.periods[0].rawPayload").doesNotExist())
        .andExpect(jsonPath("$.periods[0].providerMessageId").doesNotExist());
  }

  @Test void anonymousPayerCanCreateIntentWithoutAuthentication() throws Exception {
    when(dues.createPublicIntent(any())).thenReturn(new ApiDtos.PublicPaymentIntentResponse("intent-1", 100_000, "QUY A001 082026", "https://qr.invalid/image", "QUY CHUNG", "123456789", "Test Bank", Instant.parse("2026-08-24T01:00:00Z"), IntentStatus.PENDING));
    mvc.perform(post("/api/v1/public/payment-intents").contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Nguyễn Văn A\",\"duePeriodIds\":[\"due-1\"]}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(100_000)).andExpect(jsonPath("$.accountNumber").value("123456789"));
  }
  @Test void anonymousPayerStatusContainsNoFinancialDetails() throws Exception {
    when(dues.publicIntentStatus("intent-1")).thenReturn(new ApiDtos.PublicPaymentIntentStatusResponse("intent-1", IntentStatus.PENDING, Instant.parse("2026-08-24T01:00:00Z")));
    mvc.perform(get("/api/v1/public/payment-intents/intent-1")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("intent-1")).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.amount").doesNotExist()).andExpect(jsonPath("$.accountNumber").doesNotExist());
  }

  @Test void shareableIntentDetailsExposeOnlyPaymentInstructions() throws Exception {
    when(dues.publicIntentDetails("intent-1")).thenReturn(new ApiDtos.PublicPaymentIntentDetailsResponse("intent-1", 100_000, "AB12CD34EF", "https://qr.invalid/image", "QUY CHUNG", "123456789", "Test Bank", Instant.parse("2026-08-24T01:00:00Z"), IntentStatus.PENDING));
    mvc.perform(get("/api/v1/public/payment-intents/intent-1/details"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(100_000))
        .andExpect(jsonPath("$.transferContent").value("AB12CD34EF"))
        .andExpect(jsonPath("$.memberId").doesNotExist())
        .andExpect(jsonPath("$.rawPayload").doesNotExist());
  }
}
