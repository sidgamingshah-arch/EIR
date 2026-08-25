package com.crisil.eir.policy.fee;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The four fee treatment rules that are neither a classification lookup nor a
 * threshold: syndication bifurcation, contingent-fee timing, the hedging-cost
 * exclusion and the government subvention position.
 *
 * <p>What these four have in common is that none of them can be answered by the
 * versioned rule set of FR-201 keyed on {@code (fee_code, product, entity,
 * effective_date)}. Each needs a fact the fee code does not carry — an arithmetic
 * comparison of two shares, an event date, a policy declaration of which codes are
 * hedging costs, a term of the individual loan contract — so each is a rule rather
 * than a mapping. They live in {@code eir-policy} because they are decisions, and
 * decisions are versioned and approved; the projector consumes their output.
 *
 * <table>
 *   <caption>The four rules and where each one lives</caption>
 *   <tr><th>Requirement</th><th>Rule</th><th>Entry point</th></tr>
 *   <tr><td>FR-205</td><td>Bifurcate a disproportionate syndication fee</td>
 *       <td>{@link #bifurcateSyndicationFee}</td></tr>
 *   <tr><td>FR-206</td><td>Exclude a contingent fee at inception, recognise it on the event</td>
 *       <td>{@link #atInception} then {@link FeeTreatmentRecognition#recognise}</td></tr>
 *   <tr><td>FR-208</td><td>Exclude hedging and swap costs from every EIR stream (HB-2)</td>
 *       <td>{@link FeeTreatmentHedgingScreen}</td></tr>
 *   <tr><td>FR-209</td><td>Exclude government interest subvention unless the rate turns on it</td>
 *       <td>{@link #governmentSubvention}</td></tr>
 * </table>
 *
 * <p>FR-208's rule is a separate type rather than a method here because it holds
 * versioned state — the declared list of hedging fee codes — and because it emits an
 * invariant result each period rather than answering a question once at inception.
 *
 * <p>Deliberately out of scope, and each owned elsewhere: resolving a fee code to
 * one of the five classifications and failing an unmapped code (FR-201, FR-202); the
 * selling-versus-processing cost function (FR-203, enforced at
 * {@link FeePosting}'s own boundary); the commitment-fee drawdown threshold
 * (FR-204); the hard filter on penal charges (FR-207,
 * {@link com.crisil.eir.calc.projection.PenalChargeScreen}). Where one of those
 * decisions would have to be guessed here, these rules refuse the input by name
 * instead, so that no judgement ends up held in two places that can disagree.
 */
public final class FeeTreatmentRules {

    private FeeTreatmentRules() {
    }

    // ------------------------------------------------------------------ FR-205

    /**
     * FR-205: bifurcates a syndication fee where the arranger's fee share is
     * disproportionate to its retained share — the excess to arrangement service
     * income, the balance to the EIR.
     *
     * <p><b>The rule is arithmetic, not a flag.</b> Take a syndicate fee pool split
     * among participants. The arranger's own fee is {@code arrangerFee}, its share of
     * that pool is {@code feeShare}, and it retains {@code retainedShare} of the
     * facility on its own book. Had the pool been split in proportion to retained
     * exposure, the arranger would have earned {@code retainedShare / feeShare} of
     * what it actually earned. That proportionate part is compensation on the loan it
     * kept, so it is integral to the EIR of the retained tranche; the excess over it is
     * payment for the arranging service, a distinct performance obligation. The
     * pool total never appears — it cancels — so the rule needs only the two shares and
     * the arranger's own fee.
     *
     * <p>Two boundary positions, both load-bearing:
     *
     * <ul>
     *   <li><b>Equality is proportionate.</b> {@code feeShare == retainedShare} means
     *       the arranger earns the same effective yield on its slice as every other
     *       participant does on theirs, which is IFRS 9 B5.4.3(c)'s own description of
     *       the case that is <em>not</em> bifurcated. The whole fee is integral. This
     *       branch short-circuits rather than computing {@code fee x s / s}: the two
     *       agree arithmetically, but the boundary is the case a policy gets challenged
     *       on and it should not be capable of moving by a rounding residue.
     *   <li><b>A retained share above the fee share is also proportionate.</b> An
     *       arranger that kept 40% of the facility for 10% of the fee pool is
     *       under-rewarded, not over-rewarded; there is no excess to strip out and the
     *       whole fee is integral. Computing the ratio unguarded would give
     *       {@code 4 x fee} to the EIR and a negative service-income limb, which
     *       {@link FeeTreatmentSplit} refuses — the cap here is what stops that being
     *       an exception instead of an answer.
     * </ul>
     *
     * <p>Retaining nothing needs no special case and gets none: {@code retainedShare}
     * of zero drives the balance to zero and the whole fee to arrangement service
     * income, which is IFRS 9 B5.4.3(c)'s first limb falling out of the arithmetic.
     *
     * <p>Order of operations is {@code (fee x retainedShare) / feeShare} rather than
     * {@code fee x (retainedShare / feeShare)} on purpose. The multiplication of a
     * money amount by a decimal share is exact at working precision in every realistic
     * case, so this form rounds <b>once</b>, at the division. The other form rounds the
     * ratio and then rounds the product of that rounded ratio, and 2/7 rounded at 28
     * digits and then multiplied is not the same number as 2/7 of the amount.
     *
     * <p>ACPIR 52 is silent on syndication entirely (reference §4 item 17), so the
     * governing source here is IFRS 9 B5.4.3(c) adopted as policy under the
     * interpretive hierarchy — the same posture the whole fee master rests on.
     *
     * @param arrangerFee      the fee the arranger received; must not be a cost
     * @param feeShare         the arranger's share of the syndicate fee pool, in {@code [0,1]}
     * @param retainedShare    the arranger's retained share of the facility, in {@code [0,1]}
     */
    public static FeeTreatmentSplit bifurcateSyndicationFee(
        Money arrangerFee, BigDecimal feeShare, BigDecimal retainedShare) {

        Objects.requireNonNull(arrangerFee, "arrangerFee");
        Objects.requireNonNull(feeShare, "feeShare");
        Objects.requireNonNull(retainedShare, "retainedShare");
        FeeTreatmentSplit.requireProportion(feeShare, "feeShare");
        FeeTreatmentSplit.requireProportion(retainedShare, "retainedShare");
        if (arrangerFee.isNegative()) {
            throw new IllegalArgumentException(
                "FR-205 bifurcates a syndication fee the arranger received; " + arrangerFee
                    + " is a cost paid, and a cost has no arrangement service income limb");
        }

        Money nil = Money.zero(arrangerFee.currency());
        if (feeShare.signum() == 0) {
            // A nil fee share and a non-nil fee cannot both be true, and the division would
            // be the thing that reported it — as an ArithmeticException naming no fee code.
            if (!arrangerFee.isZero()) {
                throw new IllegalArgumentException(
                    "arranger received " + arrangerFee + " out of a nil share of the syndicate fee"
                        + " pool. One of the two figures is wrong and FR-205 cannot say which;"
                        + " the split is undefined rather than defaulting either way");
            }
            return new FeeTreatmentSplit(nil, nil, nil, feeShare, retainedShare,
                "FR-205: no syndication fee and no fee share — nothing to bifurcate");
        }

        if (feeShare.compareTo(retainedShare) <= 0) {
            return new FeeTreatmentSplit(
                arrangerFee, arrangerFee, nil, feeShare, retainedShare,
                "FR-205: fee share " + feeShare.toPlainString() + " does not exceed retained share "
                    + retainedShare.toPlainString() + ", so the arranger earns no more on its"
                    + " retained slice than the other participants earn on theirs (IFRS 9"
                    + " B5.4.3(c)); the whole fee is integral to the EIR and no excess is stripped");
        }

        // One rounding event, at the division. See the order-of-operations note above.
        Money balance = arrangerFee.times(retainedShare).dividedBy(feeShare);
        // Exact subtraction, deliberately without a MathContext: the split's reconstitution
        // guarantee is an equality, and a second limb rounded independently at working
        // precision breaks it whenever retainedShare/feeShare does not terminate — 2/7 for a
        // 10% retention against a 35% fee share, which is an ordinary consortium position.
        Money excess = new Money(
            arrangerFee.amount().subtract(balance.amount()), arrangerFee.currency());
        return new FeeTreatmentSplit(
            arrangerFee, balance, excess, feeShare, retainedShare,
            "FR-205: fee share " + feeShare.toPlainString() + " exceeds retained share "
                + retainedShare.toPlainString() + ", so " + balance + " is the fee a participant"
                + " holding the same slice would have earned and is integral to the EIR of the"
                + " retained tranche; the excess " + excess + " is payment for the arranging"
                + " service (IFRS 9 B5.4.3(c); ACPIR 52 is silent on syndication)");
    }

    // ------------------------------------------------------------------ FR-206

    /**
     * FR-206, first limb, for a fee that is <em>not</em> contingent: decides whether it
     * enters the inception projection and when it is otherwise recognised.
     *
     * <p>Present so that the batch form can return one decision per posting with no
     * gaps. A screen that returns only the contingent fees leaves the caller to infer
     * the treatment of everything else, and the inference it will make is "it was in
     * the projection", which is wrong for three of the four classifications that can
     * reach here.
     *
     * <p>Two classifications are refused by name rather than guessed, because in both
     * cases the recognition period is an input this rule does not hold, and inventing
     * one would put a single decision in two places — the failure mode
     * {@link FeePosting} itself warns about for the cost function:
     *
     * <ul>
     *   <li>{@code OVER_COMMITMENT_PERIOD} turns on the drawdown assessment and the
     *       per-product threshold of FR-204 — recognised on expiry if undrawn.
     *   <li>{@code SEPARATE_SERVICE} turns on when its performance obligation is
     *       satisfied, which is an Ind AS 115 question. Defaulting it to the posting
     *       date would recognise a multi-year advisory mandate collected up front
     *       entirely at inception.
     * </ul>
     *
     * @throws IllegalArgumentException on a commitment fee (FR-204 decides it) or a
     *     distinct performance obligation (revenue recognition decides it)
     */
    public static FeeTreatmentRecognition atInception(FeePosting posting) {
        Objects.requireNonNull(posting, "posting");
        return switch (posting.classification()) {
            case INTEGRAL -> new FeeTreatmentRecognition(
                posting.feeCode(), posting.amount(), FeeClassification.INTEGRAL, true, null, null,
                "ACPIR 52: integral to the EIR, in the inception cash flow vector and recognised"
                    + " through the rate over the expected life");
            case AS_INCURRED -> new FeeTreatmentRecognition(
                posting.feeCode(), posting.amount(), FeeClassification.AS_INCURRED, false, null,
                posting.postedOn(),
                "IFRS 9 B5.4.3 as adopted policy: not integral, recognised in profit or loss when"
                    + " incurred and outside the inception projection");
            case SEPARATE_SERVICE -> throw new IllegalArgumentException(
                "fee code " + posting.feeCode() + " is classified SEPARATE_SERVICE. It is outside"
                    + " the EIR, which this rule agrees with, but its recognition period is the"
                    + " date its performance obligation is satisfied — a revenue recognition"
                    + " question under Ind AS 115, and not an input this rule holds. Defaulting it"
                    + " to the posting date would book a three-year advisory mandate collected up"
                    + " front entirely in the inception period, over-stating income, which is the"
                    + " mirror of the understatement FR-206 exists to prevent");
            case OVER_COMMITMENT_PERIOD -> throw new IllegalArgumentException(
                "fee code " + posting.feeCode() + " is classified OVER_COMMITMENT_PERIOD, whose"
                    + " recognition turns on the drawdown assessment and the per-product threshold"
                    + " of FR-204 — recognised on expiry if undrawn — and not on the FR-206"
                    + " contingency test. This rule will not guess it");
            case EXCLUDED_BY_DIRECTION -> throw new IllegalArgumentException(
                "fee code " + posting.feeCode() + " is EXCLUDED_BY_DIRECTION; it is filtered at the"
                    + " ingestion boundary (FR-207) and asserted as invariant PC-1, and no"
                    + " recognition timing applies to it");
        };
    }

    /**
     * FR-206, first limb, for a contingent fee: excluded from the inception projection
     * and left <b>outstanding</b> against the named event.
     *
     * <p>The exclusion alone is the defect this signature is shaped to prevent. The
     * returned recognition carries the awaited event and no recognition date, so
     * {@link FeeTreatmentRecognition#outstanding()} is true and something must later
     * call {@link FeeTreatmentRecognition#recognise}; a bare boolean "excluded" would
     * have discharged the engine of that obligation and the prepayment penalty the bank
     * actually collects would never be booked.
     *
     * <p>The contingency test wins over the incoming classification rather than
     * deferring to it. A prepayment penalty that arrives {@code INTEGRAL} is a fee
     * master defect — it would put delinquency and prepayment income into the initial
     * carrying amount and move the rate for the life of the exposure — and FR-206 says
     * to exclude it, not to trust the classification and exclude it only if the
     * classification already agreed. The override is recorded in the basis so the fee
     * master defect is visible in the trace rather than absorbed silently.
     *
     * <p>A commitment fee is refused here as well as in
     * {@link #atInception(FeePosting)}, and the duplication is the point: without it,
     * the same commitment-fee posting is refused by name through one entry point and
     * silently reclassified {@code AS_INCURRED} through the other, purely because
     * somebody put its code in the contingency map. Two answers to one question,
     * chosen by which overload the caller happened to reach, is the defect these rules
     * are shaped to avoid.
     */
    public static FeeTreatmentRecognition atInception(
        FeePosting posting, FeeTreatmentContingentEvent trigger) {

        Objects.requireNonNull(posting, "posting");
        Objects.requireNonNull(trigger, "trigger");
        if (posting.classification() == FeeClassification.OVER_COMMITMENT_PERIOD) {
            throw new IllegalArgumentException(
                "fee code " + posting.feeCode() + " is classified OVER_COMMITMENT_PERIOD and is"
                    + " mapped to the contingent event " + trigger + ". A commitment fee's"
                    + " recognition turns on the drawdown assessment and the per-product threshold"
                    + " of FR-204, not on the FR-206 contingency test; one of the two mappings is"
                    + " wrong and this rule will not choose between them");
        }
        String override = posting.classification() == FeeClassification.AS_INCURRED
            ? ""
            : " The fee master classified this code " + posting.classification()
                + "; FR-206 overrides that, because a contingent charge inside the initial"
                + " carrying amount moves the rate for the whole life of the exposure.";
        return new FeeTreatmentRecognition(
            posting.feeCode(), posting.amount(), FeeClassification.AS_INCURRED, false, trigger, null,
            "FR-206: " + trigger + " is contingent — " + trigger.whyNotProjectable()
                + " — so it is excluded from the inception projection and recognised in the period"
                + " the event occurs, at the amount then realised." + override);
    }

    /**
     * FR-206 over a whole inception fee set: one decision per posting, in order.
     *
     * <p>Total coverage is the point. The two limbs of FR-206 are only checkable
     * together, and they are only together if the result of screening an inception
     * vector accounts for every posting that went in — the ones that entered the
     * projection, the ones recognised as incurred, and the ones left outstanding
     * against an event. A screen that returns a filtered list instead loses the third
     * group, which is the group FR-206 is about.
     *
     * <p>The two by-name refusals of the single-posting forms propagate here unchanged: a
     * commitment fee or a distinct performance obligation in the set fails the call rather
     * than acquiring a guessed recognition period, whether or not the fee code also appears
     * in {@code contingentTriggers}.
     *
     * @param postings          the inception fee set, already classified by the rule set
     * @param contingentTriggers fee code to contingent event, from the fee master. Codes are
     *     matched case-insensitively after trimming, because the fee master and the source
     *     feed are maintained by different teams and a code that differs only in case is
     *     the same code — silently failing to match one would put a prepayment penalty in
     *     the projection, which is the exact defect FR-206 prevents.
     */
    public static List<FeeTreatmentRecognition> atInception(
        List<FeePosting> postings, Map<String, FeeTreatmentContingentEvent> contingentTriggers) {

        Objects.requireNonNull(postings, "postings");
        Objects.requireNonNull(contingentTriggers, "contingentTriggers");
        Map<String, FeeTreatmentContingentEvent> canonical = canonicalise(contingentTriggers);
        List<FeeTreatmentRecognition> decisions = new ArrayList<>(postings.size());
        for (FeePosting posting : postings) {
            FeeTreatmentContingentEvent trigger = canonical.get(canonicalFeeCode(posting.feeCode()));
            decisions.add(trigger == null ? atInception(posting) : atInception(posting, trigger));
        }
        return List.copyOf(decisions);
    }

    // ------------------------------------------------------------------ FR-209

    /**
     * FR-209: government interest subvention is outside the EIR and accounted for
     * separately, <b>unless</b> the contract makes the borrower's own rate contingent
     * on it.
     *
     * <p>The reasoning is the one the reference file settles at §5 item 9. The EIR
     * discounts the cash flows "between the parties to the contract"; a subvention paid
     * by Government to the lender is not one of them, so it does not belong in the rate,
     * and it is recognised separately as it arises. Where the contract makes the
     * borrower's rate turn on the subvention — the borrower contractually pays 4% only
     * because the 3% subvention exists, and pays 7% if it lapses — the flow is in
     * substance part of what the borrower's own contract yields, and it comes inside.
     *
     * <p><b>The consistency clause is structural, not a rule applied three times.</b>
     * FR-209 requires the same position across agriculture, education and MSME, and the
     * way this method delivers that is by having no product, segment, scheme or portfolio
     * parameter at all: there is nowhere for a per-segment answer to enter. A Kisan Credit
     * Card subvention, a Central Sector Interest Subsidy on an education loan and an MSME
     * interest-subvention claim with the same contract term get the same treatment because
     * the same term is the only input. That is worth stating because the drift is
     * historically per-scheme — schemes arrive one at a time, each with its own circular
     * and its own project team — and three teams each reaching a defensible answer produce
     * an indefensible set.
     *
     * <p>Neither ACPIR nor the Investment Directions address subvention at all (reference
     * §4B silence 7), so this is an adopted policy position and the {@code basis} says so.
     * It affects agriculture, education and MSME books at scale, which is why the position
     * is versioned rather than implicit.
     *
     * <p>Note what this method takes: the amount, the date and the contract term — not a
     * classified {@link FeePosting}. FR-206 reads a classification the rule set already
     * resolved and decides only <em>timing</em>; FR-209 decides the
     * <em>classification itself</em> from a term of the individual loan agreement, which
     * no {@code (fee_code, product, entity, effective_date)} key can carry. Accepting an
     * already-classified posting here would invite the rule to agree or disagree with a
     * classification that had no business being made.
     *
     * @param feeCode  the subvention's code in the fee master, retained for the trace
     * @param amount   the subvention, signed from the holder's perspective as elsewhere
     * @param accruedOn the date the subvention accrued — an input, never a clock read. On
     *     the excluded branch it is the recognition date. On the in-EIR branch it cannot be,
     *     because a fee inside the projection amortises through the rate and a single
     *     recognition date on it would double count; there it is the date the flow carries in
     *     the cash flow vector, and it is retained in the {@code basis} so a quarterly
     *     accruing scheme does not produce a series of indistinguishable decisions.
     * @param borrowerRateContingentOnSubvention whether the loan contract makes the
     *     borrower's own rate depend on the subvention. A term of the contract, read from
     *     the contract, and the only thing this decision turns on.
     */
    public static FeeTreatmentRecognition governmentSubvention(
        String feeCode, Money amount, LocalDate accruedOn,
        boolean borrowerRateContingentOnSubvention) {

        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(accruedOn, "accruedOn");
        if (borrowerRateContingentOnSubvention) {
            // The accrual date cannot go in recognisedOn — an in-projection fee amortises
            // through the rate and a single date on it would recognise it twice — so it is
            // carried in the basis instead. It has to be carried somewhere: a scheme that
            // accrues quarterly over five years produces a series of these, and a series whose
            // members are indistinguishable cannot be placed in a cash flow vector or traced.
            return new FeeTreatmentRecognition(
                feeCode, amount, FeeClassification.INTEGRAL, true, null, null,
                "FR-209: the loan contract makes the borrower's own rate contingent on the"
                    + " subvention, so in substance the flow is part of what this contract yields"
                    + " to the parties and it enters the EIR, dated " + accruedOn
                    + " in the cash flow vector rather than recognised on that date. Applied on the"
                    + " contract term alone, identically across agriculture, education and MSME");
        }
        return new FeeTreatmentRecognition(
            feeCode, amount, FeeClassification.AS_INCURRED, false, null, accruedOn,
            "FR-209: a subvention received from Government is not a cash flow between the parties"
                + " to the contract, so it is excluded from the EIR and accounted for separately as"
                + " it accrues. ACPIR and the Investment Directions are both silent (reference §4B"
                + " silence 7); this is an adopted policy position. Applied on the contract term"
                + " alone, identically across agriculture, education and MSME");
    }

    // ------------------------------------------------------------------ shared

    /**
     * The one canonical form of a fee code, shared with
     * {@link FeeTreatmentHedgingScreen}.
     *
     * <p>Trim and upper-case, matching what {@link FeePosting} already does to the cost
     * function. Two rules keying on fee codes with two different notions of equality
     * would mean a code that the contingency map matches and the hedging declaration
     * misses, and the miss is silent.
     */
    static String canonicalFeeCode(String feeCode) {
        return feeCode.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, FeeTreatmentContingentEvent> canonicalise(
        Map<String, FeeTreatmentContingentEvent> triggers) {

        Map<String, FeeTreatmentContingentEvent> canonical = new LinkedHashMap<>();
        triggers.forEach((code, event) -> {
            Objects.requireNonNull(code, "contingent trigger fee code");
            Objects.requireNonNull(event, "contingent event for fee code " + code);
            FeeTreatmentContingentEvent clash = canonical.put(canonicalFeeCode(code), event);
            if (clash != null && clash != event) {
                throw new IllegalArgumentException(
                    "fee code " + code + " maps to both " + clash + " and " + event
                        + " once case is normalised; the fee master cannot say which event a fee"
                        + " awaits, and a recognition period cannot be inferred from two");
            }
        });
        return canonical;
    }
}
