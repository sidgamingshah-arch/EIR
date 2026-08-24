package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * How principal is returned. Independent of {@link InterestServicing}, which says
 * when interest is paid.
 *
 * <p>Keeping the two apart is what lets a principal-only moratorium and a fully
 * capitalising one be the same product family with one field different, instead of
 * two entries in a shape enum.
 */
public sealed interface PrincipalProfile {

    String label();

    /** Whether the profile leaves a terminal lump outstanding after the last instalment. */
    default boolean hasTerminalLump() {
        return false;
    }

    /** Equal instalments; principal is whatever is left after interest. EMI, EQI, EHI. */
    record LevelAnnuity() implements PrincipalProfile {
        @Override
        public String label() {
            return "LEVEL_ANNUITY";
        }
    }

    /**
     * Principal straight-line, so the instalment declines as interest falls. The
     * corporate-term-loan "EPI" shape.
     */
    record EqualPrincipal() implements PrincipalProfile {
        @Override
        public String label() {
            return "EQUAL_PRINCIPAL";
        }
    }

    /** All principal at maturity. */
    record BulletAtMaturity() implements PrincipalProfile {
        @Override
        public String label() {
            return "BULLET_AT_MATURITY";
        }
    }

    /**
     * Amortise toward a terminal lump rather than to zero — a balloon, or a lease
     * residual value.
     *
     * <p>Invariant ST-5: the ladder must amortise to exactly the terminal amount,
     * not to zero. A balloon schedule that reaches zero has silently amortised the
     * lump the borrower still owes.
     */
    record Balloon(Money terminalAmount) implements PrincipalProfile {

        public Balloon {
            Objects.requireNonNull(terminalAmount, "terminalAmount");
            if (!terminalAmount.isPositive()) {
                throw new IllegalArgumentException(
                    "a balloon terminal amount is positive; for none use BulletAtMaturity or"
                        + " LevelAnnuity, got " + terminalAmount);
            }
        }

        @Override
        public boolean hasTerminalLump() {
            return true;
        }

        @Override
        public String label() {
            return "BALLOON(" + terminalAmount + ")";
        }
    }

    /**
     * An explicit principal ladder. Project finance sized to projected free cash
     * flow, where no formula reproduces the schedule and it is therefore supplied.
     *
     * @param ladder principal due per period ordinal, in order
     */
    record Sculpted(List<PrincipalStep> ladder) implements PrincipalProfile {

        public Sculpted {
            Objects.requireNonNull(ladder, "ladder");
            if (ladder.isEmpty()) {
                throw new IllegalArgumentException("a sculpted ladder needs at least one step");
            }
            ladder = List.copyOf(ladder);
        }

        /** Total principal the ladder repays — checked against notional by ST-3. */
        public Money total() {
            Money sum = Money.zero(ladder.get(0).amount().currency());
            for (PrincipalStep step : ladder) {
                sum = sum.plus(step.amount());
            }
            return sum;
        }

        @Override
        public String label() {
            return "SCULPTED(" + ladder.size() + " steps)";
        }
    }

    /**
     * Instalments stepping on a contractual ladder.
     *
     * <p>The step is a <b>contractual</b> feature, not a market movement, so a
     * change to it carries a {@code STEP_UP_PREDETERMINED} driver and routes to a
     * catch-up rather than a reset. That distinction is the reason the step lives
     * here and not in {@link RateProfile}: this steps the <em>instalment</em>, a
     * coupon step steps the <em>rate</em>, and they route the same way for different
     * reasons.
     *
     * @param factor         multiplier applied at each step, e.g. 1.10 for +10%
     * @param everyNPeriods  periods between steps
     * @param direction      up or down; a factor below one with UP is rejected
     */
    record StepLadder(BigDecimal factor, int everyNPeriods, StepDirection direction)
        implements PrincipalProfile {

        public StepLadder {
            Objects.requireNonNull(factor, "factor");
            Objects.requireNonNull(direction, "direction");
            if (factor.signum() <= 0) {
                throw new IllegalArgumentException("factor must be positive, got " + factor.toPlainString());
            }
            if (everyNPeriods < 1) {
                throw new IllegalArgumentException("everyNPeriods must be >= 1, got " + everyNPeriods);
            }
            if (direction == StepDirection.UP && factor.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException(
                    "direction UP with factor " + factor.toPlainString() + " steps down; state the"
                        + " direction the factor actually implies rather than relying on the reader");
            }
            if (direction == StepDirection.DOWN && factor.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                    "direction DOWN with factor " + factor.toPlainString() + " steps up");
            }
        }

        @Override
        public String label() {
            return "STEP_" + direction + "(x" + factor.toPlainString() + " every " + everyNPeriods + ")";
        }
    }

    /**
     * Nothing until maturity: principal and all accrued interest settle in one
     * flow. The zero-coupon and deep-discount profile.
     *
     * <p>Approximation is never permitted for this profile at any tenor. The entire
     * return is accretion, and the straight-line error compounds with tenor — on a
     * 15-year instrument it overstates year-one income by 81%. Enforced as ST-12's
     * companion rule in {@link ScheduleBlueprint}.
     */
    record NoneUntilMaturity() implements PrincipalProfile {
        @Override
        public String label() {
            return "NONE_UNTIL_MATURITY";
        }
    }

    /** Direction of a {@link StepLadder}. */
    enum StepDirection {
        UP,
        DOWN
    }

    /**
     * One rung of a {@link Sculpted} ladder.
     *
     * @param periodIndex 1-based period ordinal
     * @param amount      principal due in that period
     */
    record PrincipalStep(int periodIndex, Money amount) {

        public PrincipalStep {
            Objects.requireNonNull(amount, "amount");
            if (periodIndex < 1) {
                throw new IllegalArgumentException("periodIndex is 1-based, got " + periodIndex);
            }
            if (amount.isNegative()) {
                throw new IllegalArgumentException(
                    "a sculpted step repays a non-negative amount, got " + amount);
            }
        }
    }
}
