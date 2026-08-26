package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A loan written below market, measured at fair value on its own day 1 (FR-909, reference § 5
 * item 11).
 *
 * <p><b>The reference's position, and the two facts it turns on.</b> A staff housing or directed
 * concessional loan is priced below market, so its fair value at initial recognition is below the
 * amount disbursed. The loan is recognised at that fair value <em>using a market rate as the
 * EIR</em> — not the concessional contractual rate — and the day-1 shortfall is employee
 * compensation rather than a lending loss.
 *
 * <p>Both halves matter and they fail differently. Using the contractual rate as the EIR would
 * amortise the concession back into interest income over the loan's life, which recognises revenue
 * the bank never priced for. Booking the shortfall as a lending loss puts a staff cost into
 * impairment, where it reads as credit performance. Same figure, two wrong readings.
 *
 * <p><b>Why the destination is data.</b> ACPIR 19 and 20 require the fair value and say nothing at
 * all about the day-1 difference — reference § 4 Silence 6, at {@code [MED-HIGH]}, and not
 * theoretical: for a public sector bank the staff book is large enough for the adjustment to be
 * material. So the destination is a Board-approved position, carried as a
 * {@link PolicyKind#POLICY_POSITION} version, and BM-1 asserts that one was taken rather than
 * defaulted. A difference booked somewhere because that is where the code happened to send it is a
 * policy decision taken by an implementation detail.
 *
 * <p><b>The same structure as the transition valuation, on its own date.</b> 04 § 6 deliberately
 * does not constrain {@code transition_fair_value.transition_date} to 1 April 2027, so that a
 * below-market origination uses the same row shape. {@link #asFairValueMeasurement()} produces it,
 * which keeps one storage path rather than two — and the difference between the two cases lives
 * where it belongs, in the destination rather than in the measurement.
 *
 * @param contractId       the loan
 * @param originationDate  its own day 1, not the transition date
 * @param amountDisbursed  what the borrower received
 * @param fairValue        fair value at initial recognition, below the amount disbursed
 * @param marketRate       the rate the loan is measured at, and its EIR
 * @param contractualRate  the concessional rate the borrower pays
 * @param destination      where the day-1 shortfall goes, or null while the position is open
 * @param position         the Board position authorising the destination, or null
 * @param measuredBy       who measured the fair value
 * @param reviewedBy       who reviewed it, or null
 */
public record BelowMarketOrigination(
    String contractId,
    LocalDate originationDate,
    Money amountDisbursed,
    Money fairValue,
    Rate marketRate,
    Rate contractualRate,
    DayOneDifferenceDestination destination,
    PolicyVersion position,
    String measuredBy,
    String reviewedBy) {

    public BelowMarketOrigination {
        contractId = FourEyes.requireIdentity(contractId, "contractId",
            "a below-market measurement has to name the loan");
        Objects.requireNonNull(originationDate, "originationDate");
        Objects.requireNonNull(amountDisbursed, "amountDisbursed");
        Objects.requireNonNull(fairValue, "fairValue");
        Objects.requireNonNull(marketRate, "marketRate");
        Objects.requireNonNull(contractualRate, "contractualRate");
        measuredBy = FourEyes.requireIdentity(measuredBy, "measuredBy",
            "an unattributed valuation is not evidence of one");
        reviewedBy = reviewedBy == null || reviewedBy.isBlank() ? null : reviewedBy.strip();

        if (reviewedBy != null && FourEyes.isSelfApproval(measuredBy, reviewedBy)) {
            // Omitted on the first cut and caught by the test that expected it. TransitionFairValue
            // guards this and this type is the same measurement on its own date, so the omission
            // would have left the below-market path — the one carrying a shortfall of 20% of the
            // amount disbursed — as the only fair value measurement in the codebase a single
            // person could sign off alone.
            throw new IllegalArgumentException(
                "contract " + contractId + " is valued and reviewed by '" + measuredBy
                    + "'; a valuation reviewed by the person who performed it carries the same"
                    + " single judgement it started with");
        }
        if (fairValue.compareTo(amountDisbursed) > 0) {
            // Not a below-market loan. A fair value above the amount disbursed is an above-market
            // origination — a day-1 gain — which is a different fact with a different treatment,
            // and letting it through here would produce a negative "shortfall" that reads as
            // compensation recovered from an employee.
            throw new IllegalArgumentException(
                "contract " + contractId + " has a fair value of "
                    + fairValue.atPresentationScale() + " above the "
                    + amountDisbursed.atPresentationScale() + " disbursed; that is an above-market"
                    + " origination and a day-1 gain, not a below-market concession");
        }
        if (marketRate.periodic().compareTo(contractualRate.periodic()) < 0) {
            // A market rate below the rate the borrower pays contradicts the premise: the loan
            // would be priced above market, and measuring it at the lower market rate would put
            // its fair value above par.
            throw new IllegalArgumentException(
                "contract " + contractId + " pays " + contractualRate.periodic().toPlainString()
                    + " against a market rate of " + marketRate.periodic().toPlainString()
                    + "; a loan priced above market is not a concession");
        }
        if (position != null && position.kind() != PolicyKind.POLICY_POSITION) {
            throw new IllegalArgumentException(
                "contract " + contractId + " cites a " + position.kind() + " version ("
                    + position.id() + ") as the authority for its day-1 destination; the ACPIR"
                    + " silence is closed by a policy position, and the makers of another kind of"
                    + " version were not deciding this");
        }
        if (destination == null && position != null) {
            throw new IllegalArgumentException(
                "contract " + contractId + " cites position " + position.id()
                    + " and names no destination; a version approving nothing in particular is not"
                    + " an approval");
        }
    }

    /**
     * The day-1 shortfall: what was disbursed, less what it was worth.
     *
     * <p>Positive by construction, and named a shortfall rather than a difference because its sign
     * is fixed and its direction is the substance — the bank gave up value on day 1. For a staff
     * loan this is compensation; where it goes is {@link #destination()} and is a policy decision,
     * not a property of this figure.
     */
    public Money dayOneShortfall() {
        return amountDisbursed.minus(fairValue);
    }

    /** The rate the loan is measured and amortised at: the market rate, never the concession. */
    public Rate effectiveInterestRate() {
        return marketRate;
    }

    /**
     * The concession expressed as a rate, for the disclosure.
     *
     * <p>The periodic spread the bank is foregoing. Published because the shortfall alone does not
     * show how far below market the loan is priced, and a book of small concessions on long
     * tenors and one of large concessions on short tenors can produce the same total.
     */
    public BigDecimal concessionSpread() {
        return marketRate.periodic().subtract(contractualRate.periodic());
    }

    /** Whether a Board position in force on the origination date authorises the destination. */
    public boolean destinationIsApproved() {
        return destination != null
            && position != null
            && position.isEffectiveOn(originationDate);
    }

    /**
     * The same measurement as a {@link TransitionFairValue}, for the one storage path 04 § 6
     * intends.
     *
     * <p>The technique is {@code DISCOUNTED_CASH_FLOW} and the rate is the market rate, because
     * that is what was done: the fair value was arrived at by discounting the concessional flows at
     * a market rate. It is never the paragraph 19 presumption — carrying cost as best evidence is
     * precisely the answer a below-market loan contradicts, since the whole point is that fair
     * value differs from the amount advanced.
     */
    public TransitionFairValue asFairValueMeasurement() {
        return new TransitionFairValue(contractId, originationDate, amountDisbursed, fairValue,
            ValuationTechnique.DISCOUNTED_CASH_FLOW, marketRate, null, measuredBy, reviewedBy);
    }

    /**
     * Invariant BM-1 over a population: one result, deviation the count of originations whose
     * day-1 destination was not chosen and approved.
     *
     * <p>Static and population-level because that is the only level at which the question is
     * useful — a single loan's missing position is a task, and the count is what tells a close
     * whether a material figure is sitting in an unapproved line. One result, because
     * {@link InvariantResult#conjunction} keeps only the first breach's deviation among same-id
     * results.
     */
    public static InvariantResult destinationsApproved(
        Collection<BelowMarketOrigination> originations) {
        Objects.requireNonNull(originations, "originations");
        List<String> unapproved = new ArrayList<>();
        Money exposed = Money.zero(Money.INR);
        for (BelowMarketOrigination origination : originations) {
            if (!origination.destinationIsApproved()) {
                unapproved.add(origination.contractId()
                    + (origination.destination() == null
                        ? " (no destination chosen)"
                        : " (" + origination.destination() + ", position "
                            + (origination.position() == null
                                ? "absent" : origination.position().id() + " not in force)")));
                exposed = exposed.plus(origination.dayOneShortfall());
            }
        }
        if (unapproved.isEmpty()) {
            return InvariantResult.pass(InvariantId.BM_1,
                originations.size() + " below-market originations, all with an approved day-1"
                    + " destination");
        }
        return InvariantResult.fail(InvariantId.BM_1,
            unapproved.size() + " of " + originations.size() + " below-market originations would"
                + " take a day-1 shortfall totalling " + exposed.atPresentationScale()
                + " to a destination nobody approved: " + unapproved,
            BigDecimal.valueOf(unapproved.size()));
    }

    /** A one-line audit sentence. */
    public String describe() {
        return "contract " + contractId + " originated " + originationDate + ": disbursed "
            + amountDisbursed.atPresentationScale() + ", fair value "
            + fairValue.atPresentationScale() + ", day-1 shortfall "
            + dayOneShortfall().atPresentationScale() + " at a market rate of "
            + marketRate.periodic().toPlainString() + " against a contractual "
            + contractualRate.periodic().toPlainString()
            + (destination == null ? " — NO DESTINATION CHOSEN"
                : " to " + destination
                    + (destinationIsApproved()
                        ? " under " + position.id()
                        : " with NO POSITION IN FORCE"));
    }
}
