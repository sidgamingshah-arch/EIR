package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The eight dimensions, composed.
 *
 * <p>This exists because a flat shape enum cannot express a combination, and every
 * real product is one: an education loan is a moratorium plus capitalisation plus an
 * annuity; project finance is tranched disbursement plus IDC plus a sculpted
 * principal ladder; a callable bond is a bullet plus a call schedule plus an
 * expected-life policy. Enumerating combinations multiplies the enum rather than
 * adding to it, and the flat-record alternative degrades into a pile of optional
 * fields most shapes ignore.
 *
 * <p>The existing shape projectors are not replaced. They become the preset layer —
 * what a blueprint is a preset <em>of</em> — and {@code ProductTemplates} names the
 * ACPIR product families in these terms.
 *
 * <p><b>Coherence is checked, not assumed</b> (invariant ST-11). Not every
 * combination means anything: principal that never repays until maturity, interest
 * serviced every period, and a principal-only holiday describe three mutually
 * exclusive things. An incoherent blueprint is a configuration error caught at
 * construction with the conflict named, not a runtime surprise three stages later.
 *
 * @param notional         total principal the instrument advances
 * @param currency         currency of every amount in the blueprint
 * @param valueDate        inception; the discounting anchor
 * @param statedMaturity   contractual maturity before any optionality is considered
 * @param disbursement     how principal reaches the borrower
 * @param principal        how principal is returned
 * @param servicing        when interest is paid, and whether it compounds
 * @param moratorium       the repayment holiday, if any
 * @param rate             how the contractual rate behaves
 * @param options          embedded options and the exercise policy
 * @param behaviour        how expected flows differ from contractual ones
 * @param calendar         when instalments fall due
 * @param dayCount         the day-count convention
 * @param residuePolicy    where the instalment-rounding residue lands
 * @param eclHorizonPeriods the ACPIR 46(1) horizon — the MAXIMUM contractual period
 *     including extension options. A separate input from expected life, never derived
 *     from it.
 */
public record ScheduleBlueprint(
    Money notional,
    Currency currency,
    LocalDate valueDate,
    LocalDate statedMaturity,
    DisbursementProfile disbursement,
    PrincipalProfile principal,
    InterestServicing servicing,
    Moratorium moratorium,
    RateProfile rate,
    OptionSchedule options,
    BehaviouralOverlay behaviour,
    ScheduleCalendar calendar,
    DayCountConvention dayCount,
    ResiduePolicy residuePolicy,
    int eclHorizonPeriods) {

    public ScheduleBlueprint {
        Objects.requireNonNull(notional, "notional");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(statedMaturity, "statedMaturity");
        Objects.requireNonNull(disbursement, "disbursement");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(servicing, "servicing");
        Objects.requireNonNull(moratorium, "moratorium");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(behaviour, "behaviour");
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(residuePolicy, "residuePolicy");

        if (!notional.currency().equals(currency)) {
            throw new IllegalArgumentException(
                "notional is " + notional.currency().getCurrencyCode() + " but the blueprint is "
                    + currency.getCurrencyCode());
        }
        if (!notional.isPositive()) {
            throw new IllegalArgumentException("notional must be positive, got " + notional);
        }
        if (!statedMaturity.isAfter(valueDate)) {
            throw new IllegalArgumentException(
                "statedMaturity " + statedMaturity + " must follow valueDate " + valueDate);
        }
        if (eclHorizonPeriods < 1) {
            throw new IllegalArgumentException(
                "eclHorizonPeriods must be >= 1, got " + eclHorizonPeriods);
        }

        List<String> conflicts = coherenceConflicts(
            principal, servicing, moratorium, disbursement, rate, options, behaviour, calendar);
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException(
                "incoherent blueprint (ST-11): " + String.join("; ", conflicts));
        }
    }

    /**
     * Every way this combination of dimensions contradicts itself.
     *
     * <p>Returned as a list rather than thrown one at a time so that a
     * misconfiguration is reported whole. Fixing one conflict and rediscovering the
     * next on the following run is how configuration work turns into an afternoon.
     */
    static List<String> coherenceConflicts(
        PrincipalProfile principal,
        InterestServicing servicing,
        Moratorium moratorium,
        DisbursementProfile disbursement,
        RateProfile rate,
        OptionSchedule options,
        BehaviouralOverlay behaviour,
        ScheduleCalendar calendar) {

        List<String> conflicts = new ArrayList<>();

        boolean noPrincipalUntilMaturity = principal instanceof PrincipalProfile.NoneUntilMaturity;

        if (noPrincipalUntilMaturity
            && moratorium.kind() == Moratorium.MoratoriumKind.PRINCIPAL_ONLY) {
            conflicts.add(
                "principal profile NONE_UNTIL_MATURITY already repays no principal before maturity,"
                    + " so a PRINCIPAL_ONLY holiday suspends nothing");
        }
        if (noPrincipalUntilMaturity
            && servicing instanceof InterestServicing.DiscountedUpfront
            && moratorium.isPresent()) {
            conflicts.add(
                "a discount instrument collects its interest at inception and repays principal at"
                    + " maturity, so it has no instalment stream for a holiday to suspend");
        }
        if (servicing instanceof InterestServicing.DiscountedUpfront && !noPrincipalUntilMaturity) {
            conflicts.add(
                "DISCOUNTED_UPFRONT interest belongs to an instrument redeemed in one flow;"
                    + " principal profile " + principal.label() + " amortises before then");
        }
        if (moratorium.kind() == Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED
            && !servicing.compounds()) {
            conflicts.add(
                "a FULL_INTEREST_CAPITALISED holiday requires servicing that compounds; "
                    + servicing.label() + " does not. Capitalising is worth materially more than"
                    + " deferring simple — 34.6 bp on a 12-period holiday — so the two cannot be"
                    + " used interchangeably");
        }
        if (moratorium.kind() == Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE
            && servicing.compounds()) {
            conflicts.add(
                "a FULL_INTEREST_DEFERRED_SIMPLE holiday must not compound, but servicing "
                    + servicing.label() + " does");
        }
        if (disbursement instanceof DisbursementProfile.UtilisationDriven
            && !(principal instanceof PrincipalProfile.BulletAtMaturity
                || noPrincipalUntilMaturity)) {
            conflicts.add(
                "a utilisation-driven revolver has no contractual repayment schedule, so principal"
                    + " profile " + principal.label() + " cannot apply. ACPIR 54 contemplates that"
                    + " no conventional EIR can be struck here and permits an approximation");
        }
        if (options.impliesFairValueThroughProfitOrLoss()) {
            conflicts.add(
                "a CONVERSION option on an asset fails SPPI, which sends the whole instrument to"
                    + " FVTPL where no EIR arises at all (ST-12). Run the classification gate"
                    + " before commissioning EIR work rather than building a blueprint for it");
        }
        boolean reprices = rate instanceof RateProfile.Floating
            || rate instanceof RateProfile.FloatingWithCollar
            || rate instanceof RateProfile.MarketSpreadReset;
        if (options.exercisePolicy() == ExercisePolicy.NEXT_REPRICING && !reprices) {
            conflicts.add(
                "the NEXT_REPRICING policy amortises to a repricing date, and rate profile "
                    + rate.label() + " has none. Falling back to expected life here would apply a"
                    + " different policy from the one recorded, which is exactly the substitution"
                    + " the recorded policy exists to prevent");
        }
        if (behaviour instanceof BehaviouralOverlay.RevolverBehaviour
            && !(disbursement instanceof DisbursementProfile.UtilisationDriven)) {
            conflicts.add(
                "REVOLVER_BEHAVIOUR describes a drawn-and-repaid limit; disbursement profile "
                    + disbursement.label() + " is not one");
        }
        if (calendar.frequency().requiresExplicitDates()
            && calendar.admitsPeriodicIndexing()) {
            conflicts.add(
                "calendar " + calendar.frequency() + " has unequal periods yet reports itself as"
                    + " admitting periodic indexing (ST-10)");
        }
        return conflicts;
    }

    /**
     * Whether the B5.4.4 next-repricing election is actually available.
     *
     * <p>Only a floating profile has a repricing date to amortise to. Elected on a
     * fixed-rate instrument the shortcut has no anchor, and silently falling back to
     * expected life would apply a different policy from the one recorded.
     */
    public boolean nextRepricingIsAvailable() {
        return rate instanceof RateProfile.Floating
            || rate instanceof RateProfile.FloatingWithCollar
            || rate instanceof RateProfile.MarketSpreadReset;
    }

    /** Whether any embedded option is present. */
    public boolean isOptioned() {
        return !options.isEmpty();
    }

    /** A one-line summary of the composition, for the computation trace. */
    public String describe() {
        return String.join(" + ",
            disbursement.label(),
            principal.label(),
            servicing.label(),
            moratorium.isPresent()
                ? "MORATORIUM(" + moratorium.periods() + " " + moratorium.kind() + ")"
                : "NO_MORATORIUM",
            rate.label(),
            options.isEmpty() ? "NO_OPTIONS" : "OPTIONS(" + options.options().size()
                + ", " + options.exercisePolicy() + ")",
            behaviour.label(),
            calendar.frequency().toString());
    }
}
