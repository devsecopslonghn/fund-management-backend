package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.DashboardService;
import com.fundmanagement.api.service.DuesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicController {
  private final DashboardService dashboard; private final DuesService dues;
  public PublicController(DashboardService dashboard, DuesService dues) { this.dashboard = dashboard; this.dues = dues; }
  @GetMapping("/overview") public ApiDtos.PublicOverviewResponse overview() { return dashboard.publicOverview(); }
  @GetMapping("/payment-dashboard") public ApiDtos.PublicPaymentDashboardResponse paymentDashboard(@RequestParam(required = false) Integer year) { return year == null ? dashboard.publicPaymentDashboard() : dashboard.publicPaymentDashboard(year); }
  @GetMapping("/contribution-board") public ApiDtos.PublicContributionBoardResponse contributionBoard(@RequestParam int year) { return dashboard.publicContributionBoard(year); }
  /** Name lookup returns only payable due periods. It never returns contact details or payment history. */
  @PostMapping("/payment-options") public ApiDtos.PublicPaymentOptionsResponse paymentOptions(@Valid @RequestBody ApiDtos.PublicPaymentOptionsRequest request) { return dues.publicPaymentOptions(request); }
  @GetMapping("/payment-options") public ApiDtos.PublicPaymentOptionsResponse paymentOptions(@RequestParam(required = false) String fullName, @RequestParam(required = false) String memberCode, @RequestParam(required = false) Integer year) { return dues.publicPaymentOptionsByMember(fullName, memberCode, year); }
  /** Public history is scoped by the selected active member code and returns only due-period aggregates. */
  @GetMapping("/payment-history") public ApiDtos.PublicPaymentHistoryResponse paymentHistory(@RequestParam String memberCode, @RequestParam(required = false) Integer year) { return dues.publicPaymentHistory(memberCode, year); }
  @GetMapping("/payment-history/{memberCode}") public ApiDtos.PublicPaymentHistoryResponse paymentHistoryByPath(@PathVariable String memberCode, @RequestParam(required = false) Integer year) { return dues.publicPaymentHistory(memberCode, year); }
  @GetMapping("/members/{memberCode}/payment-history") public ApiDtos.PublicPaymentHistoryResponse memberPaymentHistory(@PathVariable String memberCode, @RequestParam(required = false) Integer year) { return dues.publicPaymentHistory(memberCode, year); }
  /** Safe detail alias for a member selected from the public contribution board. */
  @GetMapping("/payment-details") public ApiDtos.PublicPaymentDetailResponse paymentDetails(@RequestParam String memberCode, @RequestParam(required = false) Integer year) { return dues.publicPaymentDetail(memberCode, year); }
  @GetMapping("/payment-details/{memberCode}") public ApiDtos.PublicPaymentDetailResponse paymentDetailsByPath(@PathVariable String memberCode, @RequestParam(required = false) Integer year) { return dues.publicPaymentDetail(memberCode, year); }
  @GetMapping("/members/{memberCode}/payment-details") public ApiDtos.PublicPaymentDetailResponse memberPaymentDetails(@PathVariable String memberCode, @RequestParam(required = false) Integer year) { return dues.publicPaymentDetail(memberCode, year); }
  @PostMapping("/payment-intents") @ResponseStatus(HttpStatus.CREATED)
  public ApiDtos.PublicPaymentIntentResponse createPaymentIntent(@Valid @RequestBody ApiDtos.PublicPaymentIntentRequest request) { return dues.createPublicIntent(request); }
  @GetMapping("/payment-intents/{id}") public ApiDtos.PublicPaymentIntentStatusResponse paymentIntentStatus(@PathVariable String id) { return dues.publicIntentStatus(id); }
  @GetMapping("/payment-intents/{id}/details") public ApiDtos.PublicPaymentIntentDetailsResponse paymentIntentDetails(@PathVariable String id) { return dues.publicIntentDetails(id); }
}
