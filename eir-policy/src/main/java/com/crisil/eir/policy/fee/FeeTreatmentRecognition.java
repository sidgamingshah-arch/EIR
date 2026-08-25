package com.crisil.eir.policy.fee;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * When a fee is recognised, and whether it enters the inception EIR projection.
 *
 * <p>The type FR-206 needs, because FR-206 is a <b>timing</b> requirement with two
 * limbs and not an exclusion: "exclude contingent fees from the inception
 * projection <em>and</em> recognise them in the period the event occurs". An
 * implementation that returns only the exclusion is not a partial implementation of
 * the requirement, it is a defect — the fee leaves the projection and nothing
 * downstream holds an obligation to book it, so a prepayment penalty the bank
 * actually collects never reaches the profit and loss account at all. Under-stated
 * income is the failure mode nobody raises a query about.
 *
 * <p>So the compact constructor makes that outcome <b>unrepresentable</b>. A
 * recognition that is outside the inception projection, carries no recognition date
 * and awaits no event is refused at construction. Every fee is in exactly one of
 * three states and there is no fourth:
 *
 * <ul>
 *   <li><b>In the projection.</b> {@code inInceptionProjection}, necessarily
 *       {@code INTEGRAL}: recognised through the effective interest rate over the
 *       expected life, so it has no single recognition date and must not be given
 *       one — a date here would recognise it twice.
 *   <li><b>Outstanding.</b> Excluded from the projection, awaiting a named
 *       {@link FeeTreatmentContingentEvent}, with no recognition date yet. This is
 *       the FR-206 state, and it is a liability of the engine: something must later
 *       call {@link #recognise}.
 *   <li><b>Recognised.</b> Outside the projection with a date, whether or not an
 *       event triggered it. A servicing fee recognised as incurred and a bounce
 *       charge recognised on the bounce are both here; the difference is that the
 *       second names the event that fixed the period.
 * </ul>
 *
 * <p>There is no fourth state, and in particular an {@code INTEGRAL} fee outside the
 * inception projection is refused from both directions: a fee that amortises through
 * the rate and also carries a single recognition date is recognised twice, and the
 * double count is reachable whichever of the two fields is set second. The
 * consequence is a real limitation and a deliberate one — a fee integral to a
 * <em>revised</em> EIR struck after a modification (FR-504) is not representable
 * here. That is a different rule's output about a different rate, and letting it
 * share this type would mean the record could no longer say what "outside the
 * inception projection" implies.
 *
 * @param feeCode              the fee master's key, retained for the trace
 * @param amount               signed from the holder's perspective, as {@code FeePosting} is
 * @param classification       the treatment this recognition asserts
 * @param inInceptionProjection whether the amount enters the inception EIR cash flow vector
 * @param awaitedEvent         the FR-206 trigger, or null where nothing is awaited
 * @param recognisedOn         the date recognised, or null while the obligation is outstanding
 * @param basis                the cited reason, retained for the computation trace
 */
public record FeeTreatmentRecognition(
    String feeCode,
    Money amount,
    FeeClassification classification,
    boolean inInceptionProjection,
    FeeTreatmentContingentEvent awaitedEvent,
    LocalDate recognisedOn,
    String basis) {

    public FeeTreatmentRecognition {
        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(basis, "basis");
        if (feeCode.isBlank()) {
            throw new IllegalArgumentException("feeCode must not be blank");
        }
        if (basis.isBlank()) {
            throw new IllegalArgumentException(
                "basis must state the rule and the reason; a recognition decision with no stated"
                    + " basis is not auditable, and every one of these decisions is challenged");
        }
        // EXCLUDED_BY_DIRECTION has exactly one route out of the engine and it is the hard
        // filter at the ingestion boundary (FR-207, invariant PC-1). FeePosting refuses to
        // carry it; a recognition carrying it would be a second, softer route to the same
        // place, and the softer route is the one a defect takes.
        if (classification == FeeClassification.EXCLUDED_BY_DIRECTION) {
            throw new IllegalArgumentException(
                "fee code " + feeCode + " is EXCLUDED_BY_DIRECTION, which is not a recognition"
                    + " timing at all: a penal charge is filtered at the ingestion boundary"
                    + " (FR-207) and asserted for the period as invariant PC-1");
        }
        if (inInceptionProjection) {
            if (classification != FeeClassification.INTEGRAL) {
                throw new IllegalArgumentException(
                    "fee code " + feeCode + " is classified " + classification
                        + " and cannot enter the inception projection; only INTEGRAL postings enter"
                        + " the initial carrying amount");
            }
            if (awaitedEvent != null) {
                throw new IllegalArgumentException(
                    "fee code " + feeCode + " cannot both enter the inception projection and await "
                        + awaitedEvent + " (FR-206 excludes a contingent fee from the projection)");
            }
            if (recognisedOn != null) {
                throw new IllegalArgumentException(
                    "fee code " + feeCode + " enters the inception projection and is therefore"
                        + " recognised through the EIR across the expected life; giving it the single"
                        + " recognition date " + recognisedOn + " would recognise it twice");
            }
        } else if (classification == FeeClassification.INTEGRAL) {
            // The same double recognition as the branch above, reached from the other side. An
            // integral fee is recognised through the rate across the expected life, so it
            // cannot also sit outside the projection with a date or an awaited event. Note this
            // deliberately excludes a fee integral to a REVISED EIR after a modification
            // (FR-504): that is a different rule's answer about a different rate.
            throw new IllegalArgumentException(
                "fee code " + feeCode + " is INTEGRAL but outside the inception projection. An"
                    + " integral fee amortises through the EIR; recognising it outside the"
                    + " projection as well recognises it twice. A fee integral to a revised EIR"
                    + " struck on a modification is FR-504's answer and not this rule's");
        } else if (awaitedEvent == null && recognisedOn == null) {
            throw new IllegalArgumentException(
                "fee code " + feeCode + " of " + amount + " is excluded from the inception"
                    + " projection with neither a recognition date nor an awaited event. That is"
                    + " income the engine has silently lost: FR-206 requires a contingent fee to be"
                    + " excluded at inception AND recognised in the period the event occurs, and the"
                    + " exclusion on its own is the half that under-states income without ever"
                    + " failing anything");
        }
    }

    /**
     * Whether the engine still owes a recognition on this fee.
     *
     * <p>The queryable form of FR-206's second limb. A period close that carries
     * outstanding recognitions is not in error — a prepayment penalty may be
     * outstanding for the whole life of the loan and then never arise — but the
     * population has to be visible, because a fee outstanding after the contract has
     * closed is a fee the engine dropped.
     */
    public boolean outstanding() {
        return awaitedEvent != null && recognisedOn == null;
    }

    /** Whether this amount enters the inception EIR cash flow vector. */
    public boolean entersEirCashFlows() {
        return inInceptionProjection;
    }

    /**
     * Discharges an outstanding obligation: the fee is recognised in the period the
     * event occurred, at the amount actually realised.
     *
     * <p>The realised amount is an input rather than the amount carried here, and
     * that is the whole reason the fee could not be projected. A prepayment penalty
     * of 2% of the balance outstanding is not knowable at inception: the balance
     * depends on when the borrower prepays, which is the event. Carrying the inception
     * estimate forward and booking that instead would recognise a figure the bank
     * never billed.
     *
     * @throws IllegalStateException if this recognition is not outstanding — either it
     *     entered the projection, or it has already been recognised. Both are caller
     *     defects rather than data conditions, so both throw.
     */
    public FeeTreatmentRecognition recognise(LocalDate eventDate, Money realised) {
        Objects.requireNonNull(eventDate, "eventDate");
        Objects.requireNonNull(realised, "realised");
        if (!outstanding()) {
            throw new IllegalStateException(
                "fee code " + feeCode + " is not awaiting an event: "
                    + (inInceptionProjection
                        ? "it entered the inception projection and amortises through the EIR"
                        : "it was already recognised on " + recognisedOn)
                    + ". Recognising it again would double-count it");
        }
        if (!realised.currency().equals(amount.currency())) {
            throw new IllegalArgumentException(
                "realised amount " + realised + " does not share the currency of the projected fee "
                    + amount);
        }
        return new FeeTreatmentRecognition(
            feeCode, realised, classification, false, awaitedEvent, eventDate,
            "FR-206: " + awaitedEvent + " occurred on " + eventDate
                + ", recognised in that period at the realised amount rather than at any figure"
                + " estimated at inception (" + awaitedEvent.whyNotProjectable() + ")");
    }

    /** One line for the computation trace. */
    public String describe() {
        String timing;
        if (inInceptionProjection) {
            timing = "in the inception projection, amortised through the EIR";
        } else if (outstanding()) {
            timing = "excluded at inception, awaiting " + awaitedEvent;
        } else {
            timing = "recognised on " + recognisedOn
                + (awaitedEvent == null ? "" : " on the " + awaitedEvent + " event");
        }
        return feeCode + " " + amount + " as " + classification + ": " + timing + ". " + basis;
    }
}
