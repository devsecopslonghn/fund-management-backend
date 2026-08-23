package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.DashboardService;
import com.fundmanagement.api.service.DuesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicController {
  private final DashboardService dashboard; private final DuesService dues;
  public PublicController(DashboardService dashboard, DuesService dues) { this.dashboard = dashboard; this.dues = dues; }
  @GetMapping("/overview") public ApiDtos.PublicOverviewResponse overview() { return dashboard.publicOverview(); }
  /** Name lookup returns only payable due periods. It never returns contact details or payment history. */
  @PostMapping("/payment-options") public ApiDtos.PublicPaymentOptionsResponse paymentOptions(@Valid @RequestBody ApiDtos.PublicPaymentOptionsRequest request) { return dues.publicPaymentOptions(request); }
  @PostMapping("/payment-intents") @ResponseStatus(HttpStatus.CREATED)
  public ApiDtos.PublicPaymentIntentResponse createPaymentIntent(@Valid @RequestBody ApiDtos.PublicPaymentIntentRequest request) { return dues.createPublicIntent(request); }
}
