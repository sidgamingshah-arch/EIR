package com.crisil.eir.application.onboarding;

import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;

/**
 * The mapping from one onboarding request and its projection onto the tier gate's subject — the
 * translation FR-411 and FR-412 needed and nobody had written.
 *
 * <p>{@code EquivalenceTestGate} has been complete in {@code eir-policy} since the tier package was
 * written, and until now had no caller outside its own package. The reason it had none was this
 * file: the gate asks for {@code (populationId, proposedTier, originalTenorMonths, inceptionAmount,
 * redemptionAmount, contractualCouponTotal)} and an earlier attempt at the wiring stopped on the
 * judgement that the lifetime coupon total was not obtainable without a guess. It is obtainable,
 * and the arithmetic is below: every figure the subject needs is a sum over the <b>contractual</b>
 * leg of a projection the pipeline has already built and has already asserted IC-1 against. Nothing
 * here estimates anything.
 *
 * <h2>The population key: {@code productId + ":" + segment}</h2>
 *
 * <p>03 § 10.2 makes the equivalence test a statement about a <em>population</em>, re-performed
 * annually, and never about one contract — so the key decides which contracts share one piece of
 * evidence. It is product-and-segment, e.g. {@code HL:RETAIL}, for two reasons:
 *
 * <ul>
 *   <li>Segment is already how {@code TierAssignmentSegment} slices the book for § 10, and it is
 *       the only segment key § 10 uses. A retail exposure and a wholesale one that happen to share
 *       a product code are different populations with different fee economics, and one test cannot
 *       evidence both.
 *   <li>Product is the granularity the test is actually performed at: the comparison of 03 § 10.2
 *       is solved-versus-approximated over a product's cash-flow shape, and a key of segment alone
 *       would let a WCDL test license a T-bill.
 * </ul>
 *
 * <p><b>Both halves matter, and a coarser key is the dangerous direction.</b> A key that is too
 * fine produces {@code NO_TEST_ON_FILE} — a demotion to Tier 2, which is more expensive and more
 * correct, plus a queue entry somebody has to clear. A key that is too coarse produces a Tier 3
 * permission granted on evidence gathered somewhere else, which is the 08 risk register's Cambodia
 * failure mode with a document attached to make it look governed.
 *
 * <h2>How the three figures are read off the vector</h2>
 *
 * <p>{@link EquivalenceTestSubject} takes an inception amount, a redemption amount <em>excluding
 * coupon</em>, and a lifetime coupon total, and computes the accretion share from them. The
 * contractual leg is partitioned by {@link FlowKind} — see {@link #legOf}, an exhaustive switch —
 * and the three figures are sums over that partition:
 *
 * <pre>
 *   inception  = |sum of DISBURSEMENT|
 *   redemption = |sum of PRINCIPAL, BALLOON, RESIDUAL_VALUE, NOTIONAL_REDEMPTION|
 *   coupon     = |sum of INTEREST|
 * </pre>
 *
 * <p>That reading, and not a shortcut through
 * {@link EquivalenceTestSubject#couponBearingAtPar}, is what makes FR-412's <b>deep-discount</b>
 * limb reachable, and getting it wrong was a defect in the first version of this file. The
 * instrument that exposes it is a fifteen-year bond bought at 315,241.70 against 1,000,000 of face
 * with a token 10,000 annual coupon: 684,758.30 of accretion against 150,000 of coupon is
 * <b>0.8203 of total return arising from accretion</b>, comfortably over the 0.50 policy share, so
 * FR-412 refuses it. Presenting it through {@code couponBearingAtPar} — which forces redemption
 * equal to inception and folds everything else into the coupon total — reports accretion of nil and
 * an accretion share of nil, and the instrument sails through the very limb written to catch it.
 * A deep-discount bond with a 1% coupon is a completely ordinary way to write the Case 9 economics
 * while defeating a zero-coupon test, so this is the Cambodia direction and not an edge case.
 *
 * <h2>The one shape that cannot be partitioned: {@code COMBINED_EMI}</h2>
 *
 * <p>An annuity vector carries no separate interest flow — the whole instalment is one
 * {@link FlowKind#COMBINED_EMI} flow — so the principal and coupon halves are not observable from
 * it. That is not a rare corner: {@code Instalment.of(date, period, amount)} defaults to
 * {@code COMBINED_EMI} and its javadoc calls that "what most retail schedules supply", and
 * {@code ExternalScheduleProjector} is layered into the registry by {@code prepend} for the
 * {@code LMS_AUTHORITATIVE} path — so undecomposed lines arrive from a live feed, not only from a
 * derived annuity.
 *
 * <p>The reading turns on <b>when the undecomposed cash comes back</b>, because that is what FR-412
 * is about. 03 § 10.3's mechanism is "the entire return is accretion and the straight-line error
 * compounds with tenor", and it needs a <em>growing</em> balance: the error is in the shape of
 * accretion onto capital that is still outstanding. Two cases, and the boundary between them is
 * observable:
 *
 * <ul>
 *   <li><b>Instalments spread over the schedule</b> — an amortising loan. Capital returns
 *       throughout, the balance declines, and there is no back-loaded accretion for straight line
 *       to mis-shape. The par reading applies: {@link EquivalenceTestSubject#couponBearingAtPar}
 *       with inception and redemption both the amount advanced and everything received above it as
 *       the coupon total. On reference case 1 that is 129,763.28 of lifetime interest and nil
 *       accretion, which is what the instrument is.
 *   <li><b>Every undecomposed flow in the final period</b> — not an instalment schedule at all, but
 *       a bullet whose principal and interest components were never identified. Read as capital
 *       returned at maturity, alongside the separated legs below. This is the limb that matters:
 *       without it, {@code Case 9}'s bond arriving from a feed as a single 1,000,000
 *       {@code COMBINED_EMI} line against 315,241.70 paid would be presented as a par loan with
 *       684,758.30 of "coupon", report accretion of nil, and clear FR-412 — the whole defect
 *       reintroduced through a flow kind.
 * </ul>
 *
 * <p><b>The limitation this leaves, stated rather than hidden.</b> A loan pool <em>purchased at a
 * discount</em> whose acquired instalments arrive undecomposed reads as a par loan with a large
 * coupon, because nothing in the vector distinguishes it from a high-yield loan advanced at par —
 * both pay the same cash on the same dates. That is the right answer for FR-412 even so: a pool
 * bought at a discount amortises it against a declining balance, which is not the growing-balance
 * error 03 § 10.3 forbids. Where a bank needs the distinction on the record, the schedule has to
 * arrive decomposed, and {@code Instalment.of(date, period, amount, kind)} is how.
 *
 * <p>A discriminant that instead looked simply for the absence of {@code INTEREST} flows would read
 * every EMI loan in the book as a zero-coupon instrument and refuse it Tier 3 on FR-412 — which is
 * why {@code COMBINED_EMI} is a partition class of its own here rather than being lumped with
 * either side.
 *
 * <p>Deciding on the <em>vector</em> rather than on {@code terms.shape()} is deliberate throughout,
 * and it is the same argument {@code EquivalenceTestSubject}'s own javadoc makes for deriving the
 * return profile from cash amounts instead of reading an {@code is_zero_coupon} flag: "An
 * instrument booked with a coupon code but no coupon flows is caught here; a flag-based test would
 * pass it." A shape of {@code BULLET} whose projector emitted no interest — a zero-rate advance
 * mis-booked as a term loan — is a discount instrument to FR-412 whatever the product master says.
 *
 * <h2>Magnitudes, not signed amounts</h2>
 *
 * <p>Money in this engine is signed from the holder's perspective, so the cash advanced on a loan
 * is negative and the receipts are positive; on a liability raised both signs invert together.
 * {@link EquivalenceTestSubject} refuses a non-positive inception amount and a negative coupon
 * total, because the accretion share it computes is a statement about <em>size</em>. So both legs
 * are summed and then taken in absolute value — the reasoning
 * {@code TierAssignmentInput.exceedsThreshold} gives for the same decision: "Comparing a signed
 * advance with a positive threshold would put every wholesale exposure below every threshold and
 * empty the Tier 1 row." Here it would empty FR-412.
 */
public final class EquivalenceTestSubjects {

    /**
     * What joins a contract to the test on file: the product code and the segment, separated.
     *
     * <p>A separator that cannot occur in either half, so the key is unambiguous. A product code of
     * {@code HL} in {@code RETAIL} and one of {@code HL:RETAIL} with no segment would otherwise
     * produce the same key and share one piece of evidence.
     */
    public static final String POPULATION_KEY_SEPARATOR = ":";

    /**
     * The product half of the key where the feed carries no product code.
     *
     * <p>{@code OnboardingRequest.productId} is nullable by design — its javadoc says a feed
     * carrying none "resolves against the per-code default rather than a product carve-out" for the
     * fee rule set. For the equivalence test there is no equivalent default and there must not be
     * one: a contract nobody can attribute to a product cannot be attributed to a population, and
     * therefore cannot be attributed to a test.
     *
     * <p>So it gets a key of its own that is unlikely to be registered against real evidence, and
     * the consequence is {@code NO_TEST_ON_FILE} — Tier 2 measurement plus a queue entry naming the
     * contract. That is the loud conservative answer. The alternative — folding unkeyed contracts
     * into the segment's evidence — would let a product-code outage silently widen every Tier 3
     * permission in the book.
     */
    public static final String UNKEYED_PRODUCT = "(no-product-code)";

    private EquivalenceTestSubjects() {
    }

    /**
     * The population this contract's Tier 3 permission is evidenced at: {@code product:SEGMENT}.
     *
     * <p>Public because the register has to be loaded under the same key it is read at. A caller
     * assembling {@code EquivalenceTestGate.of(...)} from a policy store computes the key here
     * rather than concatenating its own, for the reason {@code EquivalenceTestSubject} strips its
     * population id: "this is the key the gate joins on, and whitespace on one side of the join is
     * a silent demotion to Tier 2." A second implementation of the key would be a second, worse
     * kind of the same defect.
     */
    public static String populationIdFor(OnboardingRequest request) {
        String product = request.productId();
        String productKey = product == null || product.isBlank()
            ? UNKEYED_PRODUCT
            : product.strip();
        // segment().name() rather than toString(): the key is persisted in the equivalence-test
        // register and joined on years later, so it must not move if somebody gives the enum a
        // display form.
        return productKey + POPULATION_KEY_SEPARATOR + request.segment().name();
    }

    /**
     * The subject to put to the gate for a contract FR-107 has proposed for {@code proposedTier}.
     *
     * @param request      the contract, for the population key and the terms
     * @param projection   the projection already built and already IC-1 asserted; the
     *                     <b>contractual</b> leg is read, never the expected one — see
     *                     {@link #sumOf}
     * @param proposedTier what the tier assignment proposed, carried through unchanged. The gate
     *                     disposes; this mapper does not second-guess the proposal
     */
    static EquivalenceTestSubject subjectFor(
        OnboardingRequest request, ProjectionResult projection, MaterialityTier proposedTier) {

        FlowVector contractual = projection.contractual();
        String populationId = populationIdFor(request);
        int tenorMonths = originalTenorMonths(request.terms());
        Money advanced = inceptionAmount(request.contractId(), projection);
        Money combined = sumOf(contractual, Leg.COMBINED_RECEIPT).abs();

        if (!combined.isZero() && amortisesOverTheSchedule(contractual)) {
            // An amortising vector: undecomposed instalments spread over the schedule, so capital
            // returns throughout and the balance declines. The principal and coupon halves are not
            // observable, and the par reading is both the only defensible one and what the shape
            // is. On reference case 1: 24 x 47,073.47 = 1,129,763.28 of instalments less
            // 1,000,000.00 advanced = 129,763.28 of lifetime interest, redeeming at par, so
            // accretion is nil.
            Money allReceipts = combined
                .plus(sumOf(contractual, Leg.PRINCIPAL_RECEIPT).abs())
                .plus(sumOf(contractual, Leg.COUPON_RECEIPT).abs());
            Money lifetimeInterest = allReceipts.minus(advanced);
            if (!lifetimeInterest.isPositive()) {
                // Receipts do not exceed what was advanced, so there is no coupon leg to carry the
                // recognition profile and EquivalenceTestSubject refuses a negative coupon total
                // outright. Presented as the discount instrument it arithmetically is, and refused
                // by FR-412 — which is the answer EquivalenceTestSubject.isZeroCoupon() documents
                // for exactly this case: "an interest-free advance repayable at face has no return
                // to mis-accrete, so refusing it Tier 3 buys nothing except consistency. It is
                // refused anyway, because FR-412 is a refusal and the moment it acquires an
                // exception for 'well, this one is harmless' it becomes a threshold with an
                // undocumented boundary."
                return EquivalenceTestSubject.discountInstrument(
                    populationId, proposedTier, tenorMonths, advanced, allReceipts);
            }
            return EquivalenceTestSubject.couponBearingAtPar(
                populationId, proposedTier, tenorMonths, advanced, lifetimeInterest);
        }

        // Either the vector separates the legs — the discount instruments, the bullets, the step
        // ladders, a decomposed supplied schedule — or every undecomposed flow sits in the final
        // period, which is a bullet whose components were never identified rather than an
        // instalment schedule. Both are read the same way, and the terminal undecomposed cash is
        // capital returned at maturity: the three figures are then read as they are, so accretion
        // is redemption less inception and the accretion share means what FR-412 says it means.
        //
        // This is the branch the Case 9 zero-coupon takes (coupon nil, accretion 684,758.30, share
        // 1.0), the branch that catches a deep-discount bond wearing a token coupon (share
        // 0.8203), and the branch that catches the same bond arriving from an LMS feed as one
        // undecomposed 1,000,000 line — which the par reading would have cleared.
        Money redemption = sumOf(contractual, Leg.PRINCIPAL_RECEIPT).abs().plus(combined);
        Money coupon = sumOf(contractual, Leg.COUPON_RECEIPT).abs();
        return new EquivalenceTestSubject(
            populationId, proposedTier, tenorMonths, advanced, redemption, coupon);
    }

    /**
     * Original tenor in whole months from the schedule, <b>rounded up</b> on any part month.
     *
     * <p>Derived from {@code termPeriods} and {@code periodsPerYear} rather than from the two dates,
     * because the two dates are what {@code TierAssignmentInput.tierInput()} already reads and the
     * gate reports this figure rather than acting on it: FR-412 refuses "at any tenor" and
     * {@code EquivalenceTestSubject}'s component javadoc says the tenor is "reported, never used as
     * a carve-out". So the requirement on it is that it be right and stable, not that it agree to
     * the day with the assignment's own reading.
     *
     * <p>Rounded up for the reason {@code TierAssignmentInput.tenorMonthsBetween} gives: where two
     * readings disagree, the one that does more work is the safe one. Worked: a 91-day T-bill
     * projected as one quarterly period is {@code 1 * 12 / 4 = 3} months; {@code Case 9}'s 15-year
     * annual zero-coupon is {@code 15 * 12 / 1 = 180} months; a 24-month monthly EMI loan is
     * {@code 24 * 12 / 12 = 24}. A weekly schedule of 7 periods is {@code (84 + 51) / 52 = 2}
     * months rather than 1, which is the rounding-up direction.
     *
     * <p>Always at least 1, because {@code ContractTerms} refuses a {@code termPeriods} below 1 and
     * a {@code periodsPerYear} below 1, and {@code EquivalenceTestSubject} refuses a tenor of zero
     * as "a missing attribute rather than a very short instrument".
     */
    static int originalTenorMonths(ContractTerms terms) {
        int months = terms.termPeriods() * 12;
        int periodsPerYear = terms.periodsPerYear();
        return (months + periodsPerYear - 1) / periodsPerYear;
    }

    /**
     * What was paid or advanced at inception, as a magnitude.
     *
     * <p>The disbursement leg, not the initial carrying amount. The two differ by the net integral
     * fee, and FR-412's question is about the <em>contractual</em> return profile: a processing fee
     * received changes the yield and does not change whether the entire return is accretion.
     * {@code EquivalenceTestSubject} says the same thing from the other side — its coupon total is
     * "lifetime contractual coupon or interest, <b>excluding fees</b>".
     *
     * <p>The throw is not a data condition. {@code ProjectionSupport.inceptionLeg} emits a
     * {@code DISBURSEMENT} flow of the amount advanced unconditionally, for every projector, and
     * {@code ContractTerms} refuses a non-positive principal — so a contractual leg with nothing
     * advanced is a defect in this engine's projection layer, not a malformed contract. It is
     * refused here for the reason {@code OnboardingOutcome.requireOrderedFromTheGate} gives for
     * refusing a mis-ordered work record: "a defect in this module's own bookkeeping rather than a
     * fact about a contract". Filed as a per-contract exception it would produce one queue entry per
     * contract in a ten-million-contract run and hide the single cause behind ten million symptoms.
     */
    private static Money inceptionAmount(String contractId, ProjectionResult projection) {
        Money advanced = Money.zero(projection.contractual().currency());
        for (CashFlow flow : projection.contractual().flows()) {
            if (legOf(flow.kind()) == Leg.ADVANCE) {
                advanced = advanced.plus(flow.amount());
            }
        }
        Money magnitude = advanced.abs();
        if (!magnitude.isPositive()) {
            throw new IllegalStateException(
                "contract " + contractId + " projected a contractual leg with nothing advanced at"
                    + " inception, so there is no amount for FR-412's accretion share to be"
                    + " measured against. ProjectionSupport emits a DISBURSEMENT flow of the"
                    + " principal for every projector and ContractTerms refuses a non-positive"
                    + " principal, so this is a projection defect and not a contract the tier gate"
                    + " can decide");
        }
        return magnitude;
    }

    /**
     * Whether the undecomposed instalments actually amortise, or all sit at maturity.
     *
     * <p>The premise the par reading depends on, <b>checked rather than assumed</b>. The first
     * version of this file assumed it, and the class comment gives the instrument that exploited the
     * assumption: {@code Case 9}'s bond arriving as one undecomposed 1,000,000 line against
     * 315,241.70 paid would have been presented as a par loan with 684,758.30 of coupon, reported
     * accretion of nil, and cleared FR-412 — the defect this whole unit exists to close,
     * reintroduced through a flow kind.
     *
     * <p>"Amortises" means at least one undecomposed flow falls before the vector's last period.
     * Deliberately weak: the question is only whether capital comes back <em>during</em> the life,
     * because that is what makes the balance decline and takes the instrument outside 03 § 10.3's
     * growing-balance mechanism. A stronger test — that the instalments are level, or consistent
     * with an annuity at the contractual rate — would be re-deriving the projector's own arithmetic
     * here, and would false-refuse every stepped, restructured or irregular schedule a live feed
     * supplies.
     *
     * <p>Compared against {@link FlowVector#maxPeriodIndex()} rather than against
     * {@code terms.termPeriods()}: a supplied schedule may legitimately run short of the recorded
     * term, and the question is about the shape of the vector that will actually be solved.
     */
    private static boolean amortisesOverTheSchedule(FlowVector contractual) {
        int lastPeriod = contractual.maxPeriodIndex();
        for (CashFlow flow : contractual.flows()) {
            if (legOf(flow.kind()) == Leg.COMBINED_RECEIPT
                && !flow.amount().isZero()
                && flow.periodIndex() < lastPeriod) {
                return true;
            }
        }
        return false;
    }

    /**
     * The signed total of the contractual leg's flows falling on one side of the partition.
     *
     * <p><b>The contractual leg, never the expected one.</b> The expected leg truncates at expected
     * life and carries the balance outstanding as an {@code EXPECTED_PREPAYMENT}, so its totals are
     * a behavioural assumption. FR-412 is a statement about the instrument's contractual return
     * profile — 03 § 10.3's "the entire return is accretion" — and reading the behavioural leg would
     * make a Tier 3 refusal move when the prepayment curve was re-estimated.
     */
    private static Money sumOf(FlowVector contractual, Leg side) {
        Money total = Money.zero(contractual.currency());
        for (CashFlow flow : contractual.flows()) {
            if (legOf(flow.kind()) == side) {
                total = total.plus(flow.amount());
            }
        }
        return total;
    }

    /** Which side of the subject's arithmetic a flow falls on. */
    private enum Leg {
        /** Cash out at inception: the price paid, or the principal advanced. */
        ADVANCE,
        /** A contractual return of capital: principal, balloon, residual, notional redemption. */
        PRINCIPAL_RECEIPT,
        /** A contractual coupon or interest receipt, separately identified. */
        COUPON_RECEIPT,
        /**
         * An instalment that is not decomposed into principal and interest.
         *
         * <p>A class of its own, and that is the point. Folded into {@link #PRINCIPAL_RECEIPT} an
         * EMI loan would show 1,129,763.28 of "redemption" against 1,000,000.00 advanced and read
         * as a discount instrument; folded into {@link #COUPON_RECEIPT} it would show nil
         * redemption and read as a perpetual. Neither is the instrument. Its presence selects the
         * par reading instead — see {@link #subjectFor}.
         */
        COMBINED_RECEIPT,
        /** An integral fee or cost. Excluded from every side; see {@link #inceptionAmount}. */
        FEE
    }

    /**
     * The flow kind's side, as an exhaustive switch so a new {@link FlowKind} cannot be forgotten.
     *
     * <p>Exhaustive deliberately. A kind quietly dropped from the coupon side understates the coupon
     * total, which overstates the accretion share; one dropped from the redemption side understates
     * the accretion. Both directions move an FR-412 boundary, and one of them moves it the way that
     * lets a deep-discount instrument through.
     *
     * <p>{@code EXPECTED_PREPAYMENT} is classed as a return of capital even though the contractual
     * leg never carries one — it is the contractual balance at a truncation date, so if a projector
     * ever put one on the contractual leg it would be a redemption and not something to drop.
     *
     * <p>{@code NOTIONAL_REDEMPTION} is a return of capital for the same reason: the B5.4.4
     * shortcut treats the balance at the repricing date as a redemption, and that is precisely the
     * redemption amount FR-412 measures accretion against.
     */
    private static Leg legOf(FlowKind kind) {
        return switch (kind) {
            case DISBURSEMENT -> Leg.ADVANCE;
            case PRINCIPAL, BALLOON, RESIDUAL_VALUE, NOTIONAL_REDEMPTION, EXPECTED_PREPAYMENT ->
                Leg.PRINCIPAL_RECEIPT;
            case INTEREST -> Leg.COUPON_RECEIPT;
            case COMBINED_EMI -> Leg.COMBINED_RECEIPT;
            case INTEGRAL_FEE_RECEIVED, INTEGRAL_COST_PAID -> Leg.FEE;
        };
    }
}
