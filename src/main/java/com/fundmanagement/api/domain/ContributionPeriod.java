package com.fundmanagement.api.domain;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Canonical annual contribution period helpers.
 *
 * Periods 01..12 are calendar months. 13 and 14 are the two mandatory annual
 * bonus periods and intentionally must never be parsed as {@link YearMonth}.
 */
public final class ContributionPeriod {
  private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-4])$");
  private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

  private ContributionPeriod() {}

  public static String key(int year, int ordinal) {
    if (year < 2000 || year > 9999 || ordinal < 1 || ordinal > 14) {
      throw new IllegalArgumentException("Contribution period is invalid");
    }
    return "%04d-%02d".formatted(year, ordinal);
  }

  public static boolean isValid(String value) { return value != null && PERIOD.matcher(value).matches(); }
  public static boolean isRegularMonth(String value) { return value != null && MONTH.matcher(value).matches(); }

  public static void validate(String value) {
    if (!isValid(value)) throw new IllegalArgumentException("Period must be YYYY-01 through YYYY-14");
  }

  public static void validateRegularMonth(String value) {
    if (!isRegularMonth(value)) throw new IllegalArgumentException("Month must be YYYY-MM");
  }

  public static int year(String value) { validate(value); return Integer.parseInt(value.substring(0, 4)); }
  public static int ordinal(String value) { validate(value); return Integer.parseInt(value.substring(5, 7)); }
  public static Comparator<String> comparator() { return Comparator.comparingInt(ContributionPeriod::year).thenComparingInt(ContributionPeriod::ordinal); }

  public static String fromMonth(String month) { validateRegularMonth(month); return month; }

  /** The calendar month used to select a member contribution rate. */
  public static String rateMonth(String period) {
    validate(period);
    return ordinal(period) <= 12 ? period : "%04d-12".formatted(year(period));
  }

  public static List<String> yearPeriods(int year) {
    if (year < 2000 || year > 9999) throw new IllegalArgumentException("Year is invalid");
    return IntStream.rangeClosed(1, 14).mapToObj(ordinal -> key(year, ordinal)).toList();
  }

  public static List<String> regularYearMonths(int year) {
    if (year < 2000 || year > 9999) throw new IllegalArgumentException("Year is invalid");
    return IntStream.rangeClosed(1, 12).mapToObj(ordinal -> key(year, ordinal)).toList();
  }

  /** Returns whether a period is in a first-year plan starting at a regular month. */
  public static boolean inPlan(String period, String startMonth, int configuredCount) {
    validate(period); validateRegularMonth(startMonth);
    if (configuredCount < 1 || configuredCount > 14) throw new IllegalArgumentException("annualContributionMonths must be between 1 and 14");
    int periodYear = year(period);
    int startYear = Integer.parseInt(startMonth.substring(0, 4));
    int startOrdinal = Integer.parseInt(startMonth.substring(5, 7));
    int ordinal = ordinal(period);
    if (periodYear < startYear) return false;
    if (periodYear > startYear) return ordinal <= configuredCount;
    return ordinal >= startOrdinal && ordinal <= configuredCount;
  }
}
