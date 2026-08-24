package com.fundmanagement.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContributionPeriodTest {
  @Test void ordersRegularAndBonusPeriodsWithoutParsingBonusAsYearMonth() {
    assertThat(ContributionPeriod.yearPeriods(2026)).containsExactly(
        "2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06",
        "2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12", "2026-13", "2026-14");
    assertThat(ContributionPeriod.comparator().compare("2026-14", "2027-01")).isLessThan(0);
    assertThat(ContributionPeriod.rateMonth("2026-13")).isEqualTo("2026-12");
  }

  @Test void validatesFirstYearPlanStartingMidYear() {
    assertThat(ContributionPeriod.yearPeriods(2026).stream().filter(p -> ContributionPeriod.inPlan(p, "2026-08", 14)).toList())
        .containsExactly("2026-08", "2026-09", "2026-10", "2026-11", "2026-12", "2026-13", "2026-14");
    assertThat(ContributionPeriod.inPlan("2026-07", "2026-08", 14)).isFalse();
    assertThat(ContributionPeriod.inPlan("2027-14", "2026-08", 14)).isTrue();
  }

  @Test void rejectsCalendarParserOnlyBonusAndInvalidPeriod() {
    assertThatThrownBy(() -> ContributionPeriod.validate("2026-15")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContributionPeriod.validateRegularMonth("2026-13")).isInstanceOf(IllegalArgumentException.class);
  }
}
