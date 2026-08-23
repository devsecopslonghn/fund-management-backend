package com.fundmanagement.api.service;

import com.fundmanagement.api.config.FundProperties;
import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.MemberRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DuesService {
  private final MemberRepository members; private final DuePeriodRepository dues; private final PaymentIntentRepository intents; private final MemberService memberService; private final FundProperties properties;
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties) { this.members = members; this.dues = dues; this.intents = intents; this.memberService = memberService; this.properties = properties; }
  public ApiDtos.GenerationResult generate(String month) {
    parseMonth(month); int created = 0, skipped = 0;
    for (MemberDocument member : members.findByStatus(MemberStatus.ACTIVE)) {
      if (month.compareTo(member.baselineMonth) < 0 || dues.findByMemberIdAndMonth(member.id, month).isPresent()) { skipped++; continue; }
      DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = month; due.amountDue = memberService.rateFor(member, month); due.amountPaid = 0; due.status = DueStatus.UNPAID; dues.save(due); created++;
    }
    return new ApiDtos.GenerationResult(month, created, skipped);
  }
  public ApiDtos.PersonalOverviewResponse personalOverview(String rawToken) {
    MemberDocument member = memberService.fromToken(rawToken); List<DuePeriodDocument> periods = dues.findByMemberIdOrderByMonthAsc(member.id); long paid = periods.stream().mapToLong(p -> p.amountPaid).sum(); long owed = periods.stream().mapToLong(p -> Math.max(0, p.amountDue - p.amountPaid)).sum();
    return new ApiDtos.PersonalOverviewResponse(new ApiDtos.PersonalMemberResponse(member.id, member.fullName, member.memberCode), paid, owed, periods.stream().map(this::toResponse).toList());
  }
  public ApiDtos.PaymentIntentResponse createIntent(String rawToken, ApiDtos.CreatePaymentIntentRequest request) {
    MemberDocument member = memberService.fromToken(rawToken); Set<String> requested = new HashSet<>(request.duePeriodIds()); if (requested.size() != request.duePeriodIds().size()) throw new IllegalArgumentException("Due periods must not be duplicated");
    List<DuePeriodDocument> periods = dues.findByMemberIdAndIdIn(member.id, requested); if (periods.size() != requested.size()) throw new IllegalArgumentException("One or more due periods are invalid"); long amount = periods.stream().mapToLong(p -> Math.max(0, p.amountDue - p.amountPaid)).sum(); if (amount <= 0) throw new IllegalArgumentException("Selected periods have no outstanding balance");
    String month = periods.stream().map(p -> p.month).min(Comparator.naturalOrder()).orElseThrow(); PaymentIntentDocument intent = new PaymentIntentDocument(); intent.memberId = member.id; intent.duePeriodIds = periods.stream().map(p -> p.id).toList(); intent.totalAmount = amount; intent.transferContent = "QUY " + member.memberCode + " " + month.substring(5, 7) + month.substring(0, 4); intent.createdAt = Instant.now(); intent.expiresAt = intent.createdAt.plusSeconds(properties.getPaymentIntentMinutes() * 60L); intent.status = IntentStatus.PENDING; intents.save(intent); return toResponse(intent);
  }
  public ApiDtos.PaymentIntentResponse getIntent(String rawToken, String intentId) { MemberDocument member = memberService.fromToken(rawToken); PaymentIntentDocument intent = intents.findByIdAndMemberId(intentId, member.id).orElseThrow(() -> new IllegalArgumentException("Payment intent not found")); if (intent.status == IntentStatus.PENDING && Instant.now().isAfter(intent.expiresAt)) { intent.status = IntentStatus.EXPIRED; intents.save(intent); } return toResponse(intent); }
  public ApiDtos.DuePeriodResponse toResponse(DuePeriodDocument p) { return new ApiDtos.DuePeriodResponse(p.id, p.month, p.amountDue, p.amountPaid, p.status, p.notes); }
  private ApiDtos.PaymentIntentResponse toResponse(PaymentIntentDocument intent) { String url = properties.getQrImageBaseUrl() + "/" + properties.getBankCode() + "-" + properties.getAccountNumber() + "-compact2.png?amount=" + intent.totalAmount + "&addInfo=" + URLEncoder.encode(intent.transferContent, StandardCharsets.UTF_8) + "&accountName=" + URLEncoder.encode(properties.getAccountHolder(), StandardCharsets.UTF_8); return new ApiDtos.PaymentIntentResponse(intent.id, intent.totalAmount, intent.transferContent, url, properties.getAccountHolder(), properties.getAccountNumber(), properties.getBankName(), intent.expiresAt, intent.status); }
  private void parseMonth(String month) { try { YearMonth.parse(month); } catch (RuntimeException ex) { throw new IllegalArgumentException("Month must be YYYY-MM"); } }
}
