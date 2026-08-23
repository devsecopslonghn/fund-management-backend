package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.DashboardService;
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
  private final DashboardService dashboard;
  public PublicController(DashboardService dashboard) { this.dashboard = dashboard; }
  @GetMapping("/overview") public ApiDtos.PublicOverviewResponse overview() { return dashboard.publicOverview(); }
  /** Deliberately generic: email delivery belongs to Phase 2 and this never reveals whether an address is registered. */
  @PostMapping("/personal-link-requests") @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiDtos.AcceptedResponse requestPersonalLink(@Valid @RequestBody ApiDtos.PersonalLinkRequest request) { return new ApiDtos.AcceptedResponse("If the address is registered, a personal link will be sent."); }
}
