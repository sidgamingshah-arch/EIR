package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

/**
 * The contract attributes a projector needs, and nothing else.
 *
 * <p>Deliberately not the persisted contract version: this is the projection
 * input, framework-free and constructible in a test in one line. Everything the
 * projectors never read — staging, allowance, hedge designation, SPPI outcome —
 * stays out, so that a projector cannot come to depend on it.
 *
 * <h2>The two lives</h2>
 *
 * <p>{@code eirExpectedLifeMonths} and {@code eclHorizonMonths} are two separate
 * parameters and one is never derived from the other:
 *
 * <ul>
 *   <li><b>EIR expected life</b> (ACPIR 51) — expected life considering all
 *       contractual terms including prepayment, extension and call options. It
 *       may be materially shorter than contractual.
 *   <li><b>ECL horizon</b> (ACPIR 46(1)) — the <em>maximum contractual</em>
 *       period, including extension options.
 * </ul>
 *
 * <p>Where they differ, {@code lifeDivergenceBasis} is mandatory and the
 * constructor enforces it, because the difference is the first thing an auditor
 * asks about. Collapsing the two into one field is the most common data-model
 * defect in this domain, and it is the kind that cannot be fixed later without
 * reworking every historical computation: once a shorter behavioural life has
 * been silently used as the ECL horizon there is no record of which of the two
 * any published figure was computed on, so no recomputation can distinguish a
 * correct historical number from a wrong one. The check is therefore at
 * construction, not at review.
 *
 * <p>A zero in either field means "not stated". A pair with one side stated and
 * the other not is treated as a divergence and needs a basis too — a half-stated
 * pair is precisely the data defect FR-108 exists to catch.
 *
 * @param principal          amount advanced, or face value at maturity for a
 *                           {@link ScheduleShape#DISCOUNT_INSTRUMENT}
 * @param contractualRate    the periodic contractual rate; its
 *                           {@code periodsPerYear} must equal
 *                           {@code periodsPerYear} here, so that interest is
 *                           never computed by dividing an annual figure
 * @param termPeriods        total scheduled periods, moratorium periods included
 * @param periodsPerYear     compounding and instalment frequency
 * @param disbursementDate   initial recognition date; the flow vector's anchor
 * @param firstDueDate       first scheduled due date, which need not be one whole
 *                           period after disbursement — a broken first period is
 *                           ordinary and is what voids periodic indexing
 * @param dayCount           per instrument, defaulting per product (FR-304)
 * @param shape              selects the projector
 * @param rateType           routing keys off this, never off an observed rate
 *                           movement (FR-507)
 * @param currency           drives presentation scale
 * @param moratoriumPeriods  leading periods with no principal repayment; 0 for none
 * @param capitalisesInterestDuringMoratorium
 *                           ACPIR 9(6)(i) education loans, IDC pre-COD
 * @param balloonAmount      terminal lump sum, or null
 * @param stepFactor         instalment ladder multiplier per step, or null
 * @param residualValue      lease residual, or null
 * @param eirExpectedLifeMonths ACPIR 51 expected life in months; 0 if not stated
 * @param eclHorizonMonths   ACPIR 46(1) maximum contractual period in months; 0 if not stated
 * @param lifeDivergenceBasis why the two lives differ; mandatory where they do
 */
public record ContractTerms(
    Money principal,
    Rate contractualRate,
    int termPeriods,
    int periodsPerYear,
    LocalDate disbursementDate,
    LocalDate firstDueDate,
    DayCountConvention dayCount,
    ScheduleShape shape,
    RateType rateType,
    Currency currency,
    int moratoriumPeriods,
    boolean capitalisesInterestDuringMoratorium,
    Money balloonAmount,
    BigDecimal stepFactor,
    Money residualValue,
    int eirExpectedLifeMonths,
    int eclHorizonMonths,
    String lifeDivergenceBasis) {

    public ContractTerms {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(contractualRate, "contractualRate");
        Objects.requireNonNull(disbursementDate, "disbursementDate");
        Objects.requireNonNull(firstDueDate, "firstDueDate");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(rateType, "rateType");
        Objects.requireNonNull(currency, "currency");
        if (!principal.isPositive()) {
            throw new IllegalArgumentException("principal must be positive, got " + principal);
        }
        if (!principal.currency().equals(currency)) {
            throw new IllegalArgumentException(
                "principal currency " + principal.currency().getCurrencyCode()
                    + " does not match contract currency " + currency.getCurrencyCode());
        }
        if (termPeriods < 1) {
            throw new IllegalArgumentException("termPeriods must be >= 1, got " + termPeriods);
        }
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be >= 1, got " + periodsPerYear);
        }
        if (contractualRate.periodsPerYear() != periodsPerYear) {
            throw new IllegalArgumentException(
                "contractual rate compounds " + contractualRate.periodsPerYear()
                    + " times a year but the schedule runs " + periodsPerYear
                    + "; interest must be computed from the rate for the period the schedule is built on,"
                    + " never by dividing an annual rate");
        }
        if (!firstDueDate.isAfter(disbursementDate)) {
            throw new IllegalArgumentException(
                "firstDueDate " + firstDueDate + " must be after disbursementDate " + disbursementDate);
        }
        if (moratoriumPeriods < 0 || moratoriumPeriods >= termPeriods) {
            throw new IllegalArgumentException(
                "moratoriumPeriods must be in [0, " + (termPeriods - 1) + "], got " + moratoriumPeriods);
        }
        requireCurrency(balloonAmount, currency, "balloonAmount");
        requireCurrency(residualValue, currency, "residualValue");
        if (balloonAmount != null && balloonAmount.isNegative()) {
            throw new IllegalArgumentException("balloonAmount must not be negative, got " + balloonAmount);
        }
        if (residualValue != null && residualValue.isNegative()) {
            throw new IllegalArgumentException("residualValue must not be negative, got " + residualValue);
        }
        if (stepFactor != null && stepFactor.signum() <= 0) {
            throw new IllegalArgumentException(
                "stepFactor must be positive, got " + stepFactor.toPlainString());
        }
        if (eirExpectedLifeMonths < 0 || eclHorizonMonths < 0) {
            throw new IllegalArgumentException(
                "life parameters must be non-negative, got eir=" + eirExpectedLifeMonths
                    + " ecl=" + eclHorizonMonths);
        }
        if (eirExpectedLifeMonths != eclHorizonMonths && isBlank(lifeDivergenceBasis)) {
            throw new IllegalArgumentException(
                "EIR expected life (ACPIR 51) is " + eirExpectedLifeMonths
                    + " months and the ECL horizon (ACPIR 46(1)) is " + eclHorizonMonths
                    + " months; lifeDivergenceBasis is mandatory where the two differ, because neither"
                    + " parameter may be derived from the other and the reconciliation must be on record");
        }
    }

    /**
     * The nine attributes every projector needs, with the optional features unset
     * and the two lives left unstated.
     */
    public static ContractTerms of(
        Money principal,
        Rate contractualRate,
        int termPeriods,
        int periodsPerYear,
        LocalDate disbursementDate,
        LocalDate firstDueDate,
        DayCountConvention dayCount,
        ScheduleShape shape,
        RateType rateType) {

        return new ContractTerms(
            principal, contractualRate, termPeriods, periodsPerYear, disbursementDate, firstDueDate,
            dayCount, shape, rateType, principal.currency(),
            0, false, null, null, null, 0, 0, null);
    }

    /** A moratorium of {@code periods}, with or without interest capitalisation. */
    public ContractTerms withMoratorium(int periods, boolean capitalisesInterest) {
        return new ContractTerms(
            principal, contractualRate, termPeriods, periodsPerYear, disbursementDate, firstDueDate,
            dayCount, shape, rateType, currency, periods, capitalisesInterest,
            balloonAmount, stepFactor, residualValue,
            eirExpectedLifeMonths, eclHorizonMonths, lifeDivergenceBasis);
    }

    /** A terminal lump sum the instalments are sized against. */
    public ContractTerms withBalloon(Money balloon) {
        return new ContractTerms(
            principal, contractualRate, termPeriods, periodsPerYear, disbursementDate, firstDueDate,
            dayCount, shape, rateType, currency, moratoriumPeriods, capitalisesInterestDuringMoratorium,
            balloon, stepFactor, residualValue,
            eirExpectedLifeMonths, eclHorizonMonths, lifeDivergenceBasis);
    }

    /** The instalment ladder multiplier applied at each step. */
    public ContractTerms withStepFactor(BigDecimal factor) {
        return new ContractTerms(
            principal, contractualRate, termPeriods, periodsPerYear, disbursementDate, firstDueDate,
            dayCount, shape, rateType, currency, moratoriumPeriods, capitalisesInterestDuringMoratorium,
            balloonAmount, factor, residualValue,
            eirExpectedLifeMonths, eclHorizonMonths, lifeDivergenceBasis);
    }

    /** A lease residual receivable at maturity. */
    public ContractTerms withResidualValue(Money residual) {
        return new ContractTerms(
            principal, contractualRate, termPeriods, periodsPerYear, disbursementDate, firstDueDate,
            dayCount, shape, rateType, currency, moratoriumPeriods, capitalisesInterestDuringMoratorium,
            balloonAmount, stepFactor, residual,
            eirExpectedLifeMonths, eclHorizonMonths, lifeDivergenceBasis);
    }

    /**
     * The two lives, together with the basis for any divergence.
     *
     * <p>They are set together because they are only meaningful together: a
     * setter for one alone invites the derivation this type exists to prevent.
     */
    public ContractTerms withLives(int expectedLifeMonths, int eclHorizon, String divergenceBasis) {
        return new ContractTerms(
            principal, contractualRate, termPeriods, periodsPerYear, disbursementDate, firstDueDate,
            dayCount, shape, rateType, currency, moratoriumPeriods, capitalisesInterestDuringMoratorium,
            balloonAmount, stepFactor, residualValue,
            expectedLifeMonths, eclHorizon, divergenceBasis);
    }

    /** The contractual periodic rate — the only rate interest is ever computed from. */
    public BigDecimal periodicRate() {
        return contractualRate.periodic();
    }

    /**
     * Calendar months in one compounding period.
     *
     * @throws IllegalStateException where the frequency is not commensurable with
     *     the calendar, because period-anniversary date arithmetic then has no
     *     defined answer and the schedule must be supplied externally
     */
    public int monthsPerPeriod() {
        if (12 % periodsPerYear != 0) {
            throw new IllegalStateException(
                "periodsPerYear " + periodsPerYear + " does not divide 12, so period due dates cannot be"
                    + " derived from the calendar; supply the billed schedule instead (FR-102)");
        }
        return 12 / periodsPerYear;
    }

    /**
     * The due date of a period ordinal: the disbursement date at 0, and
     * {@code firstDueDate} advanced by whole periods thereafter.
     */
    public LocalDate dueDate(int periodIndex) {
        if (periodIndex < 0) {
            throw new IllegalArgumentException("periodIndex must be non-negative, got " + periodIndex);
        }
        if (periodIndex == 0) {
            return disbursementDate;
        }
        return firstDueDate.plusMonths((long) monthsPerPeriod() * (periodIndex - 1));
    }

    /** The final scheduled due date. */
    public LocalDate maturityDate() {
        return dueDate(termPeriods);
    }

    /** Periods carrying a principal repayment — the term less any moratorium. */
    public int repaymentPeriods() {
        return termPeriods - moratoriumPeriods;
    }

    public boolean hasMoratorium() {
        return moratoriumPeriods > 0;
    }

    /**
     * EIR expected life expressed in periods, capped at the contractual term.
     *
     * <p>An unstated expected life resolves to the contractual term, which is the
     * ACPIR 51 fallback (FR-310). That fallback is intended for rare cases and
     * must be an explicit, justified election on the contract — this method
     * cannot tell an election from an omission, which is why
     * {@link ProjectionResult#expectedEqualsContractualByPolicy()} records only
     * that the two legs coincide and the justification is held upstream.
     */
    public int expectedLifePeriods() {
        if (eirExpectedLifeMonths == 0) {
            return termPeriods;
        }
        int periods = eirExpectedLifeMonths / monthsPerPeriod();
        return Math.min(Math.max(periods, 1), termPeriods);
    }

    /** Whether the expected leg will coincide with the contractual leg. */
    public boolean expectedLifeEqualsContractual() {
        return expectedLifePeriods() >= termPeriods;
    }

    /** The balloon, or zero where none is set. */
    public Money balloonOrZero() {
        return balloonAmount == null ? Money.zero(currency) : balloonAmount;
    }

    /** The lease residual, or zero where none is set. */
    public Money residualOrZero() {
        return residualValue == null ? Money.zero(currency) : residualValue;
    }

    private static void requireCurrency(Money value, Currency expected, String field) {
        if (value != null && !value.currency().equals(expected)) {
            throw new IllegalArgumentException(
                field + " currency " + value.currency().getCurrencyCode()
                    + " does not match contract currency " + expected.getCurrencyCode());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
