package dev.buhanzaz.rwms.logistics.order.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Canonicalizes accepted human phone formatting to the E.164 representation persisted and exposed
 * by logistics-owned client and order aggregates.
 */
public final class PhoneNumberNormalizer {
  private static final Pattern HUMAN_INPUT = Pattern.compile("^(?:\\+|8)[0-9() .-]*$");
  private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");

  private PhoneNumberNormalizer() {}

  /**
   * Removes presentation punctuation, converts a Russian {@code 8XXXXXXXXXX} trunk prefix to {@code
   * +7}, and rejects a result outside the 7–15 digit E.164 envelope.
   */
  public static String normalizeRequired(String value) {
    String raw = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim();
    if (!HUMAN_INPUT.matcher(raw).matches()) {
      throw new IllegalArgumentException("phone is invalid");
    }
    boolean explicitPlus = raw.startsWith("+");
    String digits = raw.replaceAll("[^0-9]", "");
    if (!explicitPlus) {
      if (digits.length() != 11 || !digits.startsWith("8")) {
        throw new IllegalArgumentException("phone is invalid");
      }
      digits = "7" + digits.substring(1);
    }
    String normalized = "+" + digits;
    if (!E164.matcher(normalized).matches()) {
      throw new IllegalArgumentException("phone is invalid");
    }
    return normalized;
  }

  /** Returns null for an absent draft field and otherwise applies {@link #normalizeRequired}. */
  public static String normalizeOptional(String value) {
    return value == null ? null : normalizeRequired(value);
  }
}
