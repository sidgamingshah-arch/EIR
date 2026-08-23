package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Converts a date interval into a year fraction.
 *
 * <p>Sensitivity to the convention is <em>inverted</em> against tenor. On a
 * 20-year mortgage a convention error is noise; on a 7-day money-market
 * instrument a three-day error is a materially wrong rate. Short tenor is where
 * convention discipline actually bites, which is why the choice is fixed per
 * instrument class in policy and applied uniformly.
 */
public interface DayCount {

    /**
     * Year fraction from {@code start} (exclusive) to {@code end} (inclusive of
     * the elapsed interval), at {@link Precision#WORKING}.
     *
     * @throws IllegalArgumentException if {@code end} precedes {@code start}
     */
    BigDecimal yearFraction(LocalDate start, LocalDate end);

    /** The convention's conventional name, as it appears in policy documents. */
    String conventionName();
}
