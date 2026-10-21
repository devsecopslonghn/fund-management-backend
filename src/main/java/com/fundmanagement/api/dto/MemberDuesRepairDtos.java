package com.fundmanagement.api.dto;

import com.fundmanagement.api.domain.DueStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class MemberDuesRepairDtos {
  private MemberDuesRepairDtos() {}
  public record Request(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String memberCode,
      @Min(2000) @Max(9999) int year, Boolean dryRun,
      @NotBlank @Size(max = 500) String reason,
      @Pattern(regexp = "^[a-f0-9]{64}$") String previewToken) {
    public boolean previewOnly() { return !Boolean.FALSE.equals(dryRun); }
  }
  public record Period(String month, boolean created, long amountDue,
      long paidBefore, long paidAfter, DueStatus statusAfter) {}
  public record Payment(String paymentId, long amount, long creditBefore, long creditAfter) {}
  public record Response(String memberCode, int year, boolean dryRun, String previewToken,
      List<String> createdPeriods, List<Period> periods, List<Payment> payments,
      long totalReceivedBefore, long totalReceivedAfter,
      long creditBefore, long creditAfter, int bankLinksUpdated) {}
}
