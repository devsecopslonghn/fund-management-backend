package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.DuesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/{token}")
public class PersonalController {
  private final DuesService dues;
  public PersonalController(DuesService dues) { this.dues = dues; }
  @GetMapping public ApiDtos.PersonalOverviewResponse overview(@PathVariable String token) { return dues.personalOverview(token); }
  @PostMapping("/payment-intents") @ResponseStatus(HttpStatus.CREATED)
  public ApiDtos.PaymentIntentResponse createIntent(@PathVariable String token, @Valid @RequestBody ApiDtos.CreatePaymentIntentRequest request) { return dues.createIntent(token, request); }
  @GetMapping("/payment-intents/{intentId}") public ApiDtos.PaymentIntentResponse getIntent(@PathVariable String token, @PathVariable String intentId) { return dues.getIntent(token, intentId); }
}
