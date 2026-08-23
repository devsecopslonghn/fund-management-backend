package com.fundmanagement.api.controller;

import com.fundmanagement.api.service.GmailOAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Compatibility callback for the redirect URI already registered in the Google web client. */
@RestController
@RequestMapping("/api/v1/admin/integrations/gmail/oauth")
public class GmailOAuthLegacyCallbackController {
  private final GmailOAuthController delegate;
  public GmailOAuthLegacyCallbackController(GmailOAuthService oauth) { this.delegate = new GmailOAuthController(oauth); }
  @GetMapping("/callback") public ResponseEntity<Void> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code, @RequestParam(required = false) String error) { return delegate.redirect(state, code, error); }
}
