package com.crisil.eir.policy.fee;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One commitment fee awaiting its terminal event: drawdown, or expiry undrawn (FR-204).
 *
 * <p><b>Why this type exists at all.</b> The recognition FR-204 asks for fires when
 * <em>nothing happens</em>. A drawdown arrives as an event — a disbursement, a schedule, a
 * lifecycle record something downstream is already waiting for. A commitment that quietly
 * lapses generates no message of any kind, and the fee sitting in a deferred-income account
 * against it stays there until someone notices, which in practice is the auditor. Holding the
 * commitment period explicitly, with its end date, is what makes the non-event assertable.
 *
 * <p><b>Two recognition patterns, selected by the classification, and they are not variations
 * of each other.</b>
 *
 * <ul>
 *   <li>{@link FeeClassification#OVER_COMMITMENT_PERIOD} — drawdown assessed as not probable.
 *       IFRS 9 B5.4.3(b) takes the fee outside the EIR entirely: it is revenue for standing
 *       ready to lend, earned by the passage of the commitment period, so it is recognised on a
 *       time-proportion basis across that period ({@link #recognisedThrough}).
 *   <li>{@link FeeClassification#INTEGRAL} — drawdown assessed as probable. ACPIR 52 and IFRS 9
 *       B5.4.2(b) defer the fee in full into the EIR of the loan that is expected to result, so
 *       <em>nothing</em> is recognised across the commitment period. There is no asset yet to
 *       amortise against; the EBA's commitment-fee Q&amp;A puts the interim balance in other
 *       assets, not in income. {@link #recognisedThrough} is therefore zero on this limb at
 *       every date, and the whole fee moves at the terminal event.
 * </ul>
 *
 * <p>The undrawn-expiry case exists on <em>both</em> limbs, and the integral limb is the one it
 * matters on. FR-204 states the expiry rule next to the below-threshold routing, but a fee
 * deferred as integral into a loan that never materialised is the sharper case: there is no EIR
 * to amortise it through, and B5.4.2(b) says it becomes revenue on expiry. On the
 * time-proportion limb the same call normally recognises nothing, because the period has already
 * recognised the fee in full — and that is worth asserting rather than assuming, since an expiry
 * hook that recognises the fee <em>again</em> at expiry double-counts a whole year of
 * commitment-fee income.
 *
 * <p>Immutable and event-free: the recognition legs are computed from the dates rather than
 * accumulated into mutable state, so a replay of the same commitment on the same dates produces
 * the same figures (invariant DT-1).
 *
 * @param feeCode         the posting's fee code, for the trace
 * @param productId       the product whose threshold classified it
 * @param amount          the fee received, positive from the holder's perspective
 * @param commitmentStart the date the bank became party to the commitment (ACPIR 23)
 * @param commitmentEnd   the stated expiry of the commitment period; strictly after the start
 * @param classification  {@code INTEGRAL} or {@code OVER_COMMITMENT_PERIOD}
 * @param policyVersionId the {@code COMMITMENT_THRESHOLD} version that classified it
 */
public record CommitmentFeeDeferral(
    String feeCode,
    String productId,
    Money amount,
    LocalDate commitmentStart,
    LocalDate commitmentEnd,
    FeeClassification classification,
    String policyVersionId) {

    public CommitmentFeeDeferral {
        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(commitmentStart, "commitmentStart");
        Objects.requireNonNull(commitmentEnd, "commitmentEnd");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        if (classification != FeeClassification.INTEGRAL
            && classification != FeeClassification.OVER_COMMITMENT_PERIOD) {
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " carries classification " + classification
                    + "; FR-204 yields INTEGRAL or OVER_COMMITMENT_PERIOD and this type has a"
                    + " recognition pattern for each and for nothing else");
        }
        if (amount.isNegative()) {
            // ACPIR 52's limb is a commitment fee *received* to originate a loan, and the sign
            // convention of FeePosting makes an outflow negative. A negative amount here is a
            // cost paid, which is ACPIR 53 territory and routes through cost_function (FR-203),
            // not through the drawdown test. Refused rather than abs()'d: silently flipping the
            // sign would recognise a payment as income at expiry.
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " is " + amount + "; a negative posting is a cost"
                    + " paid under ACPIR 53, not a commitment fee received under ACPIR 52");
        }
        if (!commitmentEnd.isAfter(commitmentStart)) {
            // The time-proportion basis divides by the length of the commitment period. A period
            // of zero days has no denominator, and the failure mode without this guard is an
            // ArithmeticException raised deep inside a recognition run rather than a message
            // naming the contract whose dates are wrong.
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " has a commitment period from " + commitmentStart
                    + " to " + commitmentEnd + "; the time-proportion basis of IFRS 9 B5.4.3(b)"
                    + " has no denominator unless the end is strictly after the start");
        }
    }

    /** The commitment period in days — the denominator of the time-proportion basis. */
    public long commitmentDays() {
        return ChronoUnit.DAYS.between(commitmentStart, commitmentEnd);
    }

    /**
     * The fee recognised in profit or loss by {@code asOf} through the passage of the commitment
     * period, before any terminal event.
     *
     * <p>Zero on the {@code INTEGRAL} limb at every date, for the reason given on the type: a
     * fee deferred into the EIR of a loan that does not exist yet earns nothing in the meantime.
     *
     * <p>On the {@code OVER_COMMITMENT_PERIOD} limb: actual days elapsed over actual days in the
     * period, clamped at both ends. Straight-line by actual days rather than by month, because
     * commitment periods are set in the sanction letter and do not respect month boundaries — a
     * facility running 14 March to 30 November has no whole-month reading, and rounding it to one
     * moves income between reporting periods.
     *
     * <p><b>Multiply then divide.</b> {@code amount x elapsed / total}, not
     * {@code amount x (elapsed / total)}: the ratio 31/90 is a non-terminating decimal that the
     * second form truncates to 28 significant digits before scaling it up by the fee, while the
     * first form divides an exact product once. Both are within working precision, and the first
     * is the form that keeps the complement exact — recognised plus deferred is the fee, which is
     * the property the terminal-event legs depend on.
     */
    public Money recognisedThrough(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (classification == FeeClassification.INTEGRAL) {
            return Money.zero(amount.currency());
        }
        if (!asOf.isAfter(commitmentStart)) {
            return Money.zero(amount.currency());
        }
        if (!asOf.isBefore(commitmentEnd)) {
            // Capped at the fee. A reporting date beyond the stated expiry — a balance queried at
            // the next quarter end, a sweep run after the period closed — must not recognise 103%
            // of the fee. The terminal events refuse such a date outright; a read-only accrual
            // query has no reason to, so it answers with the whole fee.
            return amount;
        }
        long elapsed = ChronoUnit.DAYS.between(commitmentStart, asOf);
        return amount.times(BigDecimal.valueOf(elapsed))
            .dividedBy(BigDecimal.valueOf(commitmentDays()));
    }

    /**
     * The fee still deferred at {@code asOf} — the whole fee on the integral limb, the
     * unrecognised remainder on the time-proportion limb.
     *
     * <p>Stated as the complement of {@link #recognisedThrough} rather than computed
     * independently. Two formulae for the two halves of one fee is how the halves come to sum to
     * 99.99% of it.
     */
    public Money deferredAt(LocalDate asOf) {
        return amount.minus(recognisedThrough(asOf));
    }

    /**
     * The commitment ran its stated course undrawn — the ordinary case, dated where it belongs.
     *
     * <p>Parameterless because the date is a property of the commitment and not of the day someone
     * noticed. This is the recognition that fires when nothing happens, so whatever raises it is a
     * sweep looking for lapsed commitments, and a sweep dated 20 March would otherwise recognise a
     * fee that became revenue on 31 December — after the close that should have reported it.
     * {@link #expiresUndrawn(LocalDate)} takes a date only for the early lapse.
     */
    public CommitmentFeeRecognition expiresUndrawn() {
        return expiresUndrawn(commitmentEnd);
    }

    /**
     * The commitment lapsed undrawn: everything still deferred becomes revenue on that date
     * (FR-204; IFRS 9 B5.4.2(b) "if the commitment expires without the loan being drawn down,
     * the fee is recognised as revenue on expiry").
     *
     * <p>On the integral limb this is the whole fee, and it is income arriving in a period
     * nothing scheduled — the reason this method exists. On the time-proportion limb it is the
     * remainder, which is zero where the commitment ran its stated course and positive where it
     * lapsed early (a cancelled or reduced sanction), and in neither case does it re-recognise
     * what the period already took.
     *
     * <p><b>The date must lie within the commitment period.</b> Before the start, a commitment
     * cannot expire before the bank became party to it (ACPIR 23). After the stated end, the date
     * being offered is a <em>notification</em> date rather than an expiry date — the commitment
     * expired when it said it would, whatever day the sweep found it — and accepting it would date
     * the revenue into a later period. Not a hypothetical: on the integral limb nothing is
     * recognised until the terminal event, so a lapse notified in March would move a whole fee out
     * of the December close and into the next year's income, in the one case where no event exists
     * to contradict it. Use {@link #expiresUndrawn()} for the stated expiry.
     */
    public CommitmentFeeRecognition expiresUndrawn(LocalDate expiredOn) {
        Objects.requireNonNull(expiredOn, "expiredOn");
        if (expiredOn.isBefore(commitmentStart) || expiredOn.isAfter(commitmentEnd)) {
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " cannot expire on " + expiredOn + ", outside its"
                    + " commitment period " + commitmentStart + " to " + commitmentEnd
                    + "; a lapse found later is a notification date, not an expiry date, and the"
                    + " fee became revenue on the date the commitment ended");
        }
        Money alreadyRecognised = recognisedThrough(expiredOn);
        Money residual = amount.minus(alreadyRecognised);
        String detail = classification == FeeClassification.INTEGRAL
            ? "deferred as integral to an expected drawdown that never came; the whole fee "
                + amount + " becomes revenue on expiry (IFRS 9 B5.4.2(b)) because there is no"
                + " asset whose EIR could amortise it"
            : "time-proportion recognition to " + expiredOn + " took " + alreadyRecognised
                + " of " + amount + "; the residual " + residual + " is recognised on expiry";
        return new CommitmentFeeRecognition(feeCode, productId, expiredOn, residual,
            Money.zero(amount.currency()), alreadyRecognised, amount,
            CommitmentFeeRecognition.Trigger.EXPIRED_UNDRAWN, policyVersionId, detail);
    }

    /**
     * The facility drew down: everything still deferred leaves this policy and enters the initial
     * carrying amount of the resulting loan, to be amortised through its EIR.
     *
     * <p><b>Nothing goes to profit or loss here.</b> That is the point of the drawdown case, and
     * it holds on both limbs. On the integral limb it is what the classification was for. On the
     * time-proportion limb it is the less obvious half: the fee was being taken to income because
     * drawdown was assessed as unlikely, the assessment turned out wrong, and the part not yet
     * earned belongs in the loan's EIR from the drawdown date — the EBA's Q&amp;A position, and
     * the treatment that keeps the loan's yield right for the remainder of its life. Recognising
     * the residual as fee income on drawdown instead would front-load income into the drawdown
     * period, which is the defect B5.4.2(b) exists to prevent.
     *
     * <p>A drawdown dated after the stated expiry is refused. The commitment no longer existed to
     * be drawn against; an extension is a new commitment period with its own dates and its own
     * deferral, not a late draw on this one.
     */
    public CommitmentFeeRecognition drawnDown(LocalDate drawnOn) {
        Objects.requireNonNull(drawnOn, "drawnOn");
        if (drawnOn.isBefore(commitmentStart) || drawnOn.isAfter(commitmentEnd)) {
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " cannot be drawn on " + drawnOn + ", outside its"
                    + " commitment period " + commitmentStart + " to " + commitmentEnd
                    + "; an extended facility is a new commitment period, not a late draw");
        }
        Money alreadyRecognised = recognisedThrough(drawnOn);
        Money residual = amount.minus(alreadyRecognised);
        String detail = classification == FeeClassification.INTEGRAL
            ? "drawdown was assessed as probable and occurred; the whole fee " + amount
                + " enters the initial carrying amount of the resulting loan (ACPIR 52)"
            : "drawdown was assessed as not probable and occurred anyway; " + alreadyRecognised
                + " of " + amount + " was already earned over the commitment period and the"
                + " residual " + residual + " enters the loan's EIR from " + drawnOn;
        return new CommitmentFeeRecognition(feeCode, productId, drawnOn,
            Money.zero(amount.currency()), residual, alreadyRecognised, amount,
            CommitmentFeeRecognition.Trigger.DRAWN, policyVersionId, detail);
    }
}
