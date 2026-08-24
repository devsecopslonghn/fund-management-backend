package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.ContributionRate;
import com.fundmanagement.api.domain.ContributionPeriod;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.MemberRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemberService {
  private final MemberRepository members; private final AuditService audit;
  private MongoTemplate mongoTemplate;
  public MemberService(MemberRepository members, AuditService audit) { this.members = members; this.audit = audit; }
  @Autowired public void setMongoTemplate(MongoTemplate mongoTemplate) { this.mongoTemplate = mongoTemplate; }
  public ApiDtos.CreatedMemberResponse create(ApiDtos.MemberUpsertRequest request, String actor) {
    validateMonth(request.joinMonth()); MemberDocument member = new MemberDocument(); apply(member, request); member.createdAt = Instant.now(); member.updatedAt = member.createdAt; members.save(member); audit.record(actor, "MEMBER_CREATED", "member", member.id, Map.of(), Map.of("memberCode", member.memberCode)); return created(member);
  }
  public ApiDtos.MemberResponse update(String id, ApiDtos.MemberUpsertRequest request, String actor) {
    MemberDocument member = get(id); Map<String, Object> before = Map.of("memberCode", member.memberCode, "status", member.status.name()); apply(member, request); member.updatedAt = Instant.now(); persistExisting(member); audit.record(actor, "MEMBER_UPDATED", "member", id, before, Map.of("memberCode", member.memberCode, "status", member.status.name())); return toResponse(member);
  }
  public void deactivate(String id, String actor) { MemberDocument member = get(id); MemberStatus before = member.status; member.status = MemberStatus.DEACTIVATED; member.updatedAt = Instant.now(); persistExisting(member); audit.record(actor, "MEMBER_DEACTIVATED", "member", id, Map.of("status", before.name()), Map.of("status", member.status.name())); }
  public ApiDtos.MemberPageResponse list(int page, int size) { var result = members.findByStatusNot(MemberStatus.DEACTIVATED, PageRequest.of(page, Math.min(size, 100), Sort.by("fullName"))); return new ApiDtos.MemberPageResponse(result.getContent().stream().map(this::toResponse).toList(), result.getNumber(), result.getSize(), result.getTotalElements()); }
  public MemberDocument get(String id) { return members.findById(id).orElseThrow(() -> new IllegalArgumentException("Member not found")); }
  public String normalizeName(String value) { return value == null ? "" : Normalizer.normalize(value.trim().replaceAll("\\s+", " "), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('đ', 'd'); }
  public long rateFor(MemberDocument member, String month) { String rateMonth = ContributionPeriod.rateMonth(month); return member.contributionRates.stream().filter(r -> r.effectiveFrom.compareTo(rateMonth) <= 0 && (r.effectiveTo == null || r.effectiveTo.compareTo(rateMonth) >= 0)).reduce((a, b) -> a.effectiveFrom.compareTo(b.effectiveFrom) >= 0 ? a : b).map(r -> r.amount).orElseThrow(() -> new IllegalStateException("No contribution rate configured")); }
  public ApiDtos.MemberResponse toResponse(MemberDocument m) { long latestRate = m.contributionRates.stream().max(java.util.Comparator.comparing(r -> r.effectiveFrom)).map(r -> r.amount).orElse(0L); return new ApiDtos.MemberResponse(m.id, m.memberCode, m.fullName, m.email, m.phone, m.joinMonth, m.baselineMonth, m.status, latestRate, planStart(m), planMonths(m), m.initiallyPaidMonths == null ? List.of() : List.copyOf(m.initiallyPaidMonths)); }
  private void apply(MemberDocument member, ApiDtos.MemberUpsertRequest request) { validateMonth(request.joinMonth()); String baseline = request.baselineMonth() == null || request.baselineMonth().isBlank() ? (member.baselineMonth == null || member.baselineMonth.isBlank() ? YearMonth.parse(request.joinMonth()).plusMonths(1).toString() : member.baselineMonth) : request.baselineMonth(); validateMonth(baseline); String start = request.contributionStartMonth() == null || request.contributionStartMonth().isBlank() ? (member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? baseline : member.contributionStartMonth) : request.contributionStartMonth(); validateMonth(start); int months = request.annualContributionMonths() == null ? (member.annualContributionMonths == null ? 14 : member.annualContributionMonths) : request.annualContributionMonths(); if (months < 1 || months > 14) throw new IllegalArgumentException("annualContributionMonths must be between 1 and 14"); months = 14; List<String> initiallyPaid = request.initiallyPaidMonths() == null ? (member.initiallyPaidMonths == null ? List.of() : List.copyOf(member.initiallyPaidMonths)) : request.initiallyPaidMonths().stream().distinct().sorted(ContributionPeriod.comparator()).toList(); initiallyPaid.forEach(ContributionPeriod::validate); member.memberCode = request.memberCode().trim().toUpperCase(); member.fullName = request.fullName().trim().replaceAll("\\s+", " "); member.normalizedFullName = normalizeName(member.fullName); member.email = request.email().trim().toLowerCase(); member.phone = blankToNull(request.phone()); member.joinMonth = request.joinMonth(); member.baselineMonth = baseline; member.contributionStartMonth = start; member.annualContributionMonths = months; member.initiallyPaidMonths = new java.util.ArrayList<>(initiallyPaid); member.status = request.status() == null ? MemberStatus.ACTIVE : request.status(); if (member.contributionRates.isEmpty()) { member.contributionRates = new java.util.ArrayList<>(List.of(new ContributionRate(request.contributionAmount(), baseline, null))); } else { ContributionRate latest = member.contributionRates.stream().max(java.util.Comparator.comparing(rate -> rate.effectiveFrom)).orElseThrow(); if (latest.amount != request.contributionAmount()) { String effective = java.util.Collections.max(List.of(baseline, YearMonth.now().toString())); if (latest.effectiveFrom.compareTo(effective) < 0) latest.effectiveTo = YearMonth.parse(effective).minusMonths(1).toString(); member.contributionRates.add(new ContributionRate(request.contributionAmount(), effective, null)); } } }
  private void persistExisting(MemberDocument member) { if (member.version != null || mongoTemplate == null) { members.save(member); return; } Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(member.id), new Criteria().orOperator(Criteria.where("version").exists(false), Criteria.where("version").is(null)))); Update update = new Update().set("memberCode", member.memberCode).set("fullName", member.fullName).set("normalizedFullName", member.normalizedFullName).set("email", member.email).set("phone", member.phone).set("joinMonth", member.joinMonth).set("baselineMonth", member.baselineMonth).set("contributionStartMonth", member.contributionStartMonth).set("annualContributionMonths", member.annualContributionMonths).set("initiallyPaidMonths", member.initiallyPaidMonths).set("status", member.status).set("contributionRates", member.contributionRates).set("updatedAt", member.updatedAt).set("version", 1L); if (mongoTemplate.updateFirst(query, update, MemberDocument.class).getMatchedCount() != 1) throw new org.springframework.dao.OptimisticLockingFailureException("Member was changed by another user"); member.version = 1L; }
  private String planStart(MemberDocument member) { return member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth; }
  private int planMonths(MemberDocument member) { return 14; }
  private void validateMonth(String value) { try { YearMonth.parse(value); } catch (RuntimeException ex) { throw new IllegalArgumentException("Month must be YYYY-MM"); } }
  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private ApiDtos.CreatedMemberResponse created(MemberDocument m) { ApiDtos.MemberResponse r = toResponse(m); return new ApiDtos.CreatedMemberResponse(r.id(), r.memberCode(), r.fullName(), r.email(), r.phone(), r.joinMonth(), r.baselineMonth(), r.status(), r.contributionAmount(), r.contributionStartMonth(), r.annualContributionMonths(), r.initiallyPaidMonths()); }
}
