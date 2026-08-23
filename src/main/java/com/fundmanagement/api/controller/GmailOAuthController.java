package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.GmailOAuthService;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/gmail/oauth")
public class GmailOAuthController {
  private final GmailOAuthService oauth;
  public GmailOAuthController(GmailOAuthService oauth) { this.oauth = oauth; }
  @PostMapping("/start") public ApiDtos.GmailOAuthStartResponse start(Principal principal) { return oauth.start(principal.getName()); }
  /** Browser redirect target. It is public only because HTTP Basic credentials do not survive Google's redirect; state is one-time and bound to the initiating admin. */
  @GetMapping("/callback") public ResponseEntity<Void> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code, @RequestParam(required = false) String error) { return redirect(state, code, error); }
  @GetMapping("/status") public ApiDtos.GmailOAuthStatusResponse status() { return oauth.status(); }
  @PostMapping("/disconnect") @ResponseStatus(HttpStatus.NO_CONTENT) public void disconnect(Principal principal) { oauth.disconnect(principal.getName()); }
  ResponseEntity<Void> redirect(String state, String code, String error) { boolean connected = false; try { ApiDtos.GmailOAuthCallbackResponse response = oauth.callback(state, code, error); connected = response != null && response.connected(); } catch (RuntimeException ignored) { /* Generic browser redirect; details stay server-side. */ } return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, oauth.frontendCallbackRedirect(connected).toString()).build(); }
}
