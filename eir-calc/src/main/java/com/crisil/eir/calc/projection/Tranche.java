package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One projected drawdown on a tranched facility.
 *
 * <p>The amount is carried positive — it is the sum drawn — and
 * {@link TranchedProjector} signs it as an outflow. Keeping the input unsigned
 * avoids the class of defect where a facility is loaded with the wrong sign and
 * projects as a receipt.
 *
 * <p>These are <em>projected</em> draws, struck at financial closure. On project
 * finance they will not match actual, which is why deviation is monitored on a
 * cumulative basis against a tolerance rather than re-estimated on every drawdown
 * (FR-512): re-striking the rate on each draw churns the book for no
 * informational gain.
 *
 * @param drawnOn     the projected drawdown date
 * @param periodIndex the period ordinal of the draw; 0 for the draw at initial
 *                    recognition
 * @param amount      the sum drawn, positive
 */
public record Tranche(LocalDate drawnOn, int periodIndex, Money amount) {

    public Tranche {
        Objects.requireNonNull(drawnOn, "drawnOn");
        Objects.requireNonNull(amount, "amount");
        if (periodIndex < 0) {
            throw new IllegalArgumentException("periodIndex must be non-negative, got " + periodIndex);
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a tranche is drawn as a positive amount, got " + amount);
        }
    }

    public static Tranche of(LocalDate drawnOn, int periodIndex, Money amount) {
        return new Tranche(drawnOn, periodIndex, amount);
    }
}
