package com.crisil.eir.policy.fee;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.PenalChargeScreen;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The positive assertion behind invariant HB-2: no hedging or swap cost is present
 * in any EIR cash flow stream (FR-208).
 *
 * <p>ACPIR 53 excludes hedging costs from transaction costs because they are
 * <em>financing</em> costs, and 03 §12 spells out why the exclusion has to be
 * proved rather than assumed: "some ALM teams think in all-in hedged cost and some
 * finance systems let them book it that way." A swap cost inside an EIR cash flow
 * stream is a named failure mode in its own right — 02 §4 lists it against FR-208
 * and HB-2, and 01 §11 item 23 states the consequence, that it "corrupts the rate".
 * It corrupts it permanently: the rate is solved once and persisted at 12 decimal
 * places (FR-404), so a swap premium folded into the inception vector misstates
 * interest in every period through to maturity, and nothing later in the
 * amortisation disagrees with it.
 *
 * <p><b>Shaped after {@link PenalChargeScreen} deliberately.</b> Both are exclusions
 * the engine must assert positively rather than trust, both have the same two
 * routes, and both fail the same way if only the first route is screened:
 *
 * <ul>
 *   <li>The <b>classified-fee route</b>, {@link #overFeePostings}, sees fee codes and
 *       can say whether a declared hedging code entered the initial carrying amount.
 *   <li>The <b>billed-schedule route</b>, {@link #overBilledSchedule}, sees nothing.
 *       A {@code LMS_AUTHORITATIVE} feed arrives as instalment amounts with no
 *       components (FR-102), and an all-in hedged rate is precisely the case where
 *       the swap cost has already been dissolved into the interest line by the source
 *       system. There is nothing left to detect, so the assertion rests on the
 *       screening having happened upstream and an {@link PenalChargeScreen.Attestation}
 *       recording it. Absent one, HB-2 is reported <b>unsatisfied</b> and not thrown:
 *       an unattested feed is a control gap to surface at the period close, not a
 *       reason to abort an otherwise sound projection.
 * </ul>
 *
 * <p>The attestation record is reused from {@link PenalChargeScreen} rather than
 * duplicated. Its four fields — which feed, screened by whom, when, and how much was
 * removed — are a generic declaration that somebody looked, and the question "why are
 * there two identical attestation records in this codebase" has no good answer. What
 * differs between PC-1 and HB-2 is what was screened for, and that is in the detail
 * text the assertion emits, where an auditor reads it.
 *
 * <p><b>Why the declared code list is mandatory.</b> Nothing in the five values of
 * {@link com.crisil.eir.domain.FeeClassification} means "financing cost", and the
 * cost-function vocabulary on {@link FeePosting} — selling, processing, admin, other —
 * has no hedging member either, so a hedging cost is identifiable only from the fee
 * master. That list is versioned policy and it is supplied; there is no default set.
 * A default would be the worst of the options here: a bank's swap premium code is
 * whatever its treasury system calls it, a built-in list would miss it, and a screen
 * that can never match anything still emits a pass. HB-2 has to be evidence, so an
 * empty declaration is refused at construction and the declaration's version travels
 * into every assertion detail — a reader can then tell whether a pass means "nothing
 * was there" or "nothing could have been found".
 */
public final class FeeTreatmentHedgingScreen {

    private final String declarationVersion;
    private final Set<String> declaredCodes;

    /**
     * @param declarationVersion the policy version that declared these codes, so an
     *     assertion emitted today can be read against the list that was in force. A
     *     {@link com.crisil.eir.policy.PolicyVersion} identifier in production.
     * @param hedgingFeeCodes the fee master's hedging and swap cost codes — swap
     *     premiums, the fixed leg of an interest rate swap, cross-currency swap cost,
     *     forward points, hedge break costs. Canonicalised by trim and upper-case, the
     *     same way {@link FeeTreatmentRules} canonicalises a contingency map, so that a
     *     code differing only in case cannot slip past one rule while matching another.
     */
    public FeeTreatmentHedgingScreen(String declarationVersion, Collection<String> hedgingFeeCodes) {
        Objects.requireNonNull(declarationVersion, "declarationVersion");
        Objects.requireNonNull(hedgingFeeCodes, "hedgingFeeCodes");
        if (declarationVersion.isBlank()) {
            throw new IllegalArgumentException(
                "declarationVersion must name the policy version that declared the hedging codes;"
                    + " an assertion that cannot be read against a dated list is not evidence");
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String code : hedgingFeeCodes) {
            Objects.requireNonNull(code, "hedging fee code");
            if (code.isBlank()) {
                throw new IllegalArgumentException(
                    "a blank hedging fee code cannot match anything and hides the fact that the"
                        + " declaration is incomplete");
            }
            canonical.add(FeeTreatmentRules.canonicalFeeCode(code));
        }
        if (canonical.isEmpty()) {
            // Placed after the loop so a declaration of nothing but blanks reports the blank
            // first: that is the more specific defect and the one the maintainer can fix.
            throw new IllegalArgumentException(
                "the hedging fee code declaration is empty, so this screen can never match a"
                    + " posting and would emit a pass on every period regardless of what entered"
                    + " the vector. HB-2 must be evidence that the ACPIR 53 exclusion held, and an"
                    + " empty declaration is indistinguishable from nobody having screened");
        }
        this.declarationVersion = declarationVersion;
        // Not Set.copyOf: its iteration order is randomised per JVM instance, and
        // declaredCodes() is public. FeePosting.COST_FUNCTIONS states this codebase's rule —
        // a breach report or a declaration listing that varies between identical runs is not
        // a record. Declaration order is the order the fee master lists them in.
        this.declaredCodes = Collections.unmodifiableSet(canonical);
    }

    /** The declared codes, canonicalised, in the order the declaration lists them. */
    public Set<String> declaredCodes() {
        return declaredCodes;
    }

    /** The policy version that declared them. */
    public String declarationVersion() {
        return declarationVersion;
    }

    /** Whether the fee master declares this code a hedging or swap cost. */
    public boolean isHedgingCost(String feeCode) {
        Objects.requireNonNull(feeCode, "feeCode");
        return declaredCodes.contains(FeeTreatmentRules.canonicalFeeCode(feeCode));
    }

    /**
     * HB-2 over a classified fee set: satisfied where no declared hedging cost entered
     * the initial carrying amount.
     *
     * <p>The test is {@link FeePosting#entersInitialCarryingAmount()} and not merely
     * "a hedging posting is present". A swap premium classified {@code AS_INCURRED} is
     * being treated exactly as ACPIR 53 requires — a financing cost in profit or loss,
     * outside the rate — and failing HB-2 on it would make the control fire on the
     * correct answer. What breaches is a hedging cost that is {@code INTEGRAL}, because
     * that is the one that reaches the inception vector and moves the solved rate.
     *
     * <p>The pass detail says how many hedging postings were seen and confirmed outside
     * the stream, not just that none breached. That distinction is the whole value of a
     * positive assertion: "three swap costs were present and all three stayed out of the
     * rate" is evidence the exclusion is working, where "nothing found" may equally mean
     * the treasury feed never arrived.
     *
     * <p>The deviation is the signed sum of the offending amounts rather than a count,
     * because that sum is the amount by which the inception net cash flow — and so the
     * solved rate — is wrong, and it is what someone has to correct. It is a bare
     * magnitude: {@link InvariantResult#deviation()} carries no currency, so the detail
     * names each offending posting with its own currency alongside. Where the offending
     * postings span more than one currency there is no such sum to report and the
     * deviation falls back to a count, with the detail saying so — a signed INR-plus-USD
     * total is a figure nobody can reconcile, which is the position
     * {@link InvariantResult#ofMoney} takes for the same reason.
     */
    public InvariantResult overFeePostings(List<FeePosting> fees) {
        Objects.requireNonNull(fees, "fees");
        List<FeePosting> hedging = new ArrayList<>();
        List<FeePosting> inStream = new ArrayList<>();
        for (FeePosting fee : fees) {
            if (isHedgingCost(fee.feeCode())) {
                hedging.add(fee);
                if (fee.entersInitialCarryingAmount()) {
                    inStream.add(fee);
                }
            }
        }
        String declaration = " (declaration " + declarationVersion + ", " + declaredCodes.size()
            + " code(s); " + fees.size() + " posting(s) screened)";
        if (inStream.isEmpty()) {
            String found = hedging.isEmpty()
                ? "no posting matched a declared hedging or swap fee code"
                : hedging.size() + " declared hedging cost(s) present, none of them integral, so"
                    + " none entered an EIR cash flow stream (ACPIR 53: financing costs)";
            return InvariantResult.pass(InvariantId.HB_2, found + declaration);
        }
        BigDecimal leaked = BigDecimal.ZERO;
        Set<Currency> currencies = new LinkedHashSet<>();
        StringBuilder offenders = new StringBuilder();
        for (FeePosting fee : inStream) {
            leaked = leaked.add(fee.amount().amount());
            currencies.add(fee.amount().currency());
            if (!offenders.isEmpty()) {
                offenders.append(", ");
            }
            offenders.append(fee.feeCode()).append(' ').append(fee.amount());
        }
        // A signed sum across currencies is a figure nobody can reconcile, and it under-states
        // whichever leg is the larger — the position InvariantResult.ofMoney takes at length
        // when it refuses to report a deviation measured across two currencies. Foreign
        // currency loans are exactly where this arises: ACPIR 101(3) and reference §4 item 18
        // put ECB and buyer's credit hedging costs in scope, so a book carrying INR and USD
        // swap costs together is the ordinary case. Where the breach spans currencies the
        // deviation falls back to a count and the detail says so, so that nobody reads a
        // hybrid as money; the per-posting amounts above carry their own currencies.
        boolean oneCurrency = currencies.size() == 1;
        String currencyNote = oneCurrency ? "" : ". The deviation is a count of " + inStream.size()
            + " posting(s) and not an amount, because the breach spans " + currencies.size()
            + " currencies and a cross-currency total would not reconcile";
        return InvariantResult.fail(
            InvariantId.HB_2,
            inStream.size() + " declared hedging or swap cost(s) are classified INTEGRAL and have"
                + " entered the EIR cash flow stream: " + offenders
                + ". ACPIR 53 excludes hedging costs as financing costs, and the rate is solved once"
                + " and persisted (FR-404), so this misstates interest in every period to maturity"
                + currencyNote + declaration,
            oneCurrency ? leaked : BigDecimal.valueOf(inStream.size()));
    }

    /**
     * HB-2 over an externally-supplied billed schedule.
     *
     * <p>Satisfied only where an attestation accompanies the feed. A billed instalment
     * carries a net amount and no components, so an all-in hedged rate — the very thing
     * 03 §12 warns some finance systems permit — is undetectable here. The assertion
     * rests on the upstream screening, and the absence of an attestation is the finding
     * rather than an error. An attestation naming a <em>different</em> feed is the same
     * finding: it is evidence about that feed and none about this one.
     *
     * @param sourceLabel the feed, named in the assertion so a breach is actionable
     * @param lineCount   how many billed lines were taken on trust
     * @param attestation the upstream screening declaration, or null where there is none
     */
    public InvariantResult overBilledSchedule(
        String sourceLabel, int lineCount, PenalChargeScreen.Attestation attestation) {

        Objects.requireNonNull(sourceLabel, "sourceLabel");
        if (attestation == null) {
            return InvariantResult.fail(
                InvariantId.HB_2,
                "externally-supplied schedule (" + sourceLabel + ", " + lineCount + " line(s))"
                    + " carries no hedging-cost attestation. A billed line arrives as a net amount"
                    + " with no components, so a swap cost already dissolved into an all-in hedged"
                    + " rate by the source system cannot be detected here (03 §12). HB-2 requires"
                    + " the ACPIR 53 exclusion to be asserted, and nothing in this feed asserts it",
                BigDecimal.valueOf(lineCount));
        }
        // An attestation for a different feed is not evidence about this one. At a period
        // close screening several feeds, where treasury attested one of them, a mis-paired
        // call would otherwise emit HB-2 evidence naming the feed nobody screened — the exact
        // control gap this method exists to surface, dressed as a pass. A finding rather than
        // a throw, because a mis-paired attestation is a data condition at the close.
        if (!sourceLabel.trim().equalsIgnoreCase(attestation.sourceSystem().trim())) {
            return InvariantResult.fail(
                InvariantId.HB_2,
                "schedule (" + sourceLabel + ", " + lineCount + " line(s)) is accompanied by an"
                    + " attestation for a different feed, " + attestation.sourceSystem()
                    + ", screened by " + attestation.screenedBy() + " on "
                    + attestation.screenedOn() + ". Nothing attests to " + sourceLabel
                    + " itself, so HB-2 is unsupported on it",
                BigDecimal.valueOf(lineCount));
        }
        return InvariantResult.pass(
            InvariantId.HB_2,
            "schedule from " + attestation.sourceSystem() + " screened for hedging and swap cost by "
                + attestation.screenedBy() + " on " + attestation.screenedOn()
                + "; excluded amount removed " + attestation.amountRemoved() + " (" + lineCount
                + " line(s))");
    }

    /**
     * The single HB-2 result for a projection, combining every route that applies.
     *
     * <p>Same reasoning as {@link PenalChargeScreen#combine}, and the same reason it is
     * not merely tidiness: a projection screened along both routes produces two HB-2
     * results, and on an unattested LMS feed they disagree — the fee route passing
     * because the fee master resolved every posting, the schedule route failing because
     * nothing attested to the feed. HB-2 is a named control an auditor asks for by name;
     * it gets one answer per period and that answer is the conjunction. The details
     * concatenate, because a pass on one route means something different from a pass on
     * both.
     *
     * <p>Refusing a foreign invariant id is the part specific to this screen: a caller
     * must not be able to fold an unrelated control into the hedging answer.
     */
    public static InvariantResult combine(List<InvariantResult> assertions) {
        Objects.requireNonNull(assertions, "assertions");
        if (assertions.isEmpty()) {
            throw new IllegalArgumentException(
                "HB-2 must be asserted on every projection; an empty list is not a pass");
        }
        for (InvariantResult assertion : assertions) {
            if (assertion.id() != InvariantId.HB_2) {
                throw new IllegalArgumentException(
                    "only HB-2 assertions combine here, got " + assertion.id());
            }
        }
        return InvariantResult.conjunction(assertions);
    }
}
