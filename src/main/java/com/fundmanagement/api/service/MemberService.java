package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.ContributionPeriod;
import com.fundmanagement.api.domain.ContributionRate;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.MemberRepository;
import java.text.Normalizer;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class MemberService {
  private final MemberRepository members;
  private final AuditService audit;
  private MongoTemplate mongoTemplate;

  public MemberService(MemberRepository members, AuditService audit) {
    this.members = members;
    this.audit = audit;
  }

  @Autowired
  public void setMongoTemplate(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  public ApiDtos.CreatedMemberResponse create(ApiDtos.MemberUpsertRequest request, String actor) {
    validateMonth(request.joinMonth());
    MemberDocument member = new MemberDocument();
    apply(member, request);
    member.createdAt = Instant.now();
    member.updatedAt = member.createdAt;
    members.save(member);
    audit.record(actor, "MEMBER_CREATED", "member", member.id, Map.of(), Map.of("memberCode", member.memberCode));
    return created(member);
  }

  public ApiDtos.MemberResponse update(String id, ApiDtos.MemberUpsertRequest request, String actor) {
    MemberDocument member = get(id);
    Map<String, Object> before = Map.of(
        "memberCode", member.memberCode,
        "status", member.status.name(),
        "contributionRates", rateSummary(member));
    apply(member, request);
    member.updatedAt = Instant.now();
    persistExisting(member);
    audit.record(actor, "MEMBER_UPDATED", "member", id, before, Map.of(
        "memberCode", member.memberCode,
        "status", member.status.name(),
        "contributionRates", rateSummary(member)));
    return toResponse(member);
  }

  public void deactivate(String id, String actor) {
    MemberDocument member = get(id);
    MemberStatus before = member.status;
    member.status = MemberStatus.DEACTIVATED;
    member.updatedAt = Instant.now();
    persistExisting(member);
    audit.record(actor, "MEMBER_DEACTIVATED", "member", id,
        Map.of("status", before.name()), Map.of("status", member.status.name()));
  }

  public ApiDtos.MemberPageResponse list(int page, int size) {
    var result = members.findByStatusNot(MemberStatus.DEACTIVATED,
        PageRequest.of(page, Math.min(size, 100), Sort.by("fullName")));
    return new ApiDtos.MemberPageResponse(result.getContent().stream().map(this::toResponse).toList(),
        result.getNumber(), result.getSize(), result.getTotalElements());
  }

  public MemberDocument get(String id) {
    return members.findById(id).orElseThrow(() -> new IllegalArgumentException("Member not found"));
  }

  public String normalizeName(String value) {
    return value == null ? "" : Normalizer.normalize(value.trim().replaceAll("\\s+", " "), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('đ', 'd');
  }

  public long rateFor(MemberDocument member, String month) {
    String rateMonth = ContributionPeriod.rateMonth(month);
    return rates(member).stream()
        .filter(rate -> rate.effectiveFrom.compareTo(rateMonth) <= 0
            && (rate.effectiveTo == null || rate.effectiveTo.compareTo(rateMonth) >= 0))
        .max(Comparator.comparing(rate -> rate.effectiveFrom))
        .map(rate -> rate.amount)
        .orElseThrow(() -> new IllegalStateException("No contribution rate configured"));
  }

  public ApiDtos.MemberResponse toResponse(MemberDocument member) {
    List<ContributionRate> rates = rates(member);
    long latestRate = rates.stream().max(Comparator.comparing(rate -> rate.effectiveFrom))
        .map(rate -> rate.amount).orElse(0L);
    List<ApiDtos.ContributionRateResponse> rateResponses = rates.stream()
        .map(rate -> new ApiDtos.ContributionRateResponse(rate.amount, rate.effectiveFrom, rate.effectiveTo))
        .toList();
    return new ApiDtos.MemberResponse(member.id, member.memberCode, member.fullName, member.email, member.phone,
        member.joinMonth, member.baselineMonth, member.status, latestRate, planStart(member), planMonths(member),
        member.initiallyPaidMonths == null ? List.of() : List.copyOf(member.initiallyPaidMonths), rateResponses);
  }

  private void apply(MemberDocument member, ApiDtos.MemberUpsertRequest request) {
    validateMonth(request.joinMonth());
    String baseline = request.baselineMonth() == null || request.baselineMonth().isBlank()
        ? (member.baselineMonth == null || member.baselineMonth.isBlank()
            ? YearMonth.parse(request.joinMonth()).plusMonths(1).toString() : member.baselineMonth)
        : request.baselineMonth();
    validateMonth(baseline);
    String start = request.contributionStartMonth() == null || request.contributionStartMonth().isBlank()
        ? (member.contributionStartMonth == null || member.contributionStartMonth.isBlank()
            ? baseline : member.contributionStartMonth)
        : request.contributionStartMonth();
    validateMonth(start);
    int months = request.annualContributionMonths() == null
        ? (member.annualContributionMonths == null ? 14 : member.annualContributionMonths)
        : request.annualContributionMonths();
    if (months < 1 || months > 14) {
      throw new IllegalArgumentException("annualContributionMonths must be between 1 and 14");
    }
    // The current SRS requires 12 regular periods plus bonus periods 13 and 14.
    months = 14;
    List<String> initiallyPaid = request.initiallyPaidMonths() == null
        ? (member.initiallyPaidMonths == null ? List.of() : List.copyOf(member.initiallyPaidMonths))
        : request.initiallyPaidMonths().stream().distinct().sorted(ContributionPeriod.comparator()).toList();
    initiallyPaid.forEach(ContributionPeriod::validate);

    String effectiveFrom = request.contributionEffectiveFrom();
    if (effectiveFrom == null || effectiveFrom.isBlank()) {
      // Preserve the old API behavior for callers that do not send the new field.
      effectiveFrom = rates(member).isEmpty() ? baseline : YearMonth.now().toString();
    }
    validateMonth(effectiveFrom);
    if (effectiveFrom.compareTo(baseline) < 0) {
      throw new IllegalArgumentException("Contribution effective month cannot be before baseline month");
    }
    if (rates(member).isEmpty() && !effectiveFrom.equals(baseline)) {
      throw new IllegalArgumentException("The first contribution rate must start at the baseline month");
    }

    member.memberCode = request.memberCode().trim().toUpperCase();
    member.fullName = request.fullName().trim().replaceAll("\\s+", " ");
    member.normalizedFullName = normalizeName(member.fullName);
    member.email = request.email().trim().toLowerCase();
    member.phone = blankToNull(request.phone());
    member.joinMonth = request.joinMonth();
    member.baselineMonth = baseline;
    member.contributionStartMonth = start;
    member.annualContributionMonths = months;
    member.initiallyPaidMonths = new ArrayList<>(initiallyPaid);
    member.status = request.status() == null ? MemberStatus.ACTIVE : request.status();
    updateContributionRates(member, request.contributionAmount(), effectiveFrom);
  }

  private void updateContributionRates(MemberDocument member, long amount, String effectiveFrom) {
    Map<String, ContributionRate> byStart = new HashMap<>();
    for (ContributionRate rate : rates(member)) {
      validateMonth(rate.effectiveFrom);
      if (rate.amount < 1) {
        throw new IllegalArgumentException("Contribution rate must be positive");
      }
      byStart.put(rate.effectiveFrom, rate);
    }
    byStart.put(effectiveFrom, new ContributionRate(amount, effectiveFrom, null));

    List<ContributionRate> ordered = new ArrayList<>(byStart.values());
    ordered.sort(Comparator.comparing(rate -> rate.effectiveFrom));
    for (int index = 0; index < ordered.size(); index++) {
      ContributionRate current = ordered.get(index);
      current.effectiveTo = index + 1 < ordered.size()
          ? YearMonth.parse(ordered.get(index + 1).effectiveFrom).minusMonths(1).toString() : null;
    }
    member.contributionRates = ordered;
  }

  private List<ContributionRate> rates(MemberDocument member) {
    return member.contributionRates == null ? List.of() : member.contributionRates;
  }

  private List<String> rateSummary(MemberDocument member) {
    return rates(member).stream()
        .map(rate -> rate.amount + "@" + rate.effectiveFrom + ".." + (rate.effectiveTo == null ? "" : rate.effectiveTo))
        .toList();
  }

  private void persistExisting(MemberDocument member) {
    if (member.version != null || mongoTemplate == null) {
      members.save(member);
      return;
    }
    Query query = Query.query(new Criteria().andOperator(
        Criteria.where("_id").is(member.id),
        new Criteria().orOperator(Criteria.where("version").exists(false), Criteria.where("version").is(null))));
    Update update = new Update().set("memberCode", member.memberCode).set("fullName", member.fullName)
        .set("normalizedFullName", member.normalizedFullName).set("email", member.email).set("phone", member.phone)
        .set("joinMonth", member.joinMonth).set("baselineMonth", member.baselineMonth)
        .set("contributionStartMonth", member.contributionStartMonth)
        .set("annualContributionMonths", member.annualContributionMonths)
        .set("initiallyPaidMonths", member.initiallyPaidMonths).set("status", member.status)
        .set("contributionRates", member.contributionRates).set("updatedAt", member.updatedAt).set("version", 1L);
    if (mongoTemplate.updateFirst(query, update, MemberDocument.class).getMatchedCount() != 1) {
      throw new org.springframework.dao.OptimisticLockingFailureException("Member was changed by another user");
    }
    member.version = 1L;
  }

  private String planStart(MemberDocument member) {
    return member.contributionStartMonth == null || member.contributionStartMonth.isBlank()
        ? member.baselineMonth : member.contributionStartMonth;
  }

  private int planMonths(MemberDocument member) {
    return 14;
  }

  private void validateMonth(String value) {
    try {
      YearMonth.parse(value);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Month must be YYYY-MM");
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private ApiDtos.CreatedMemberResponse created(MemberDocument member) {
    ApiDtos.MemberResponse response = toResponse(member);
    return new ApiDtos.CreatedMemberResponse(response.id(), response.memberCode(), response.fullName(), response.email(),
        response.phone(), response.joinMonth(), response.baselineMonth(), response.status(), response.contributionAmount(),
        response.contributionStartMonth(), response.annualContributionMonths(), response.initiallyPaidMonths(),
        response.contributionRates());
  }
}
