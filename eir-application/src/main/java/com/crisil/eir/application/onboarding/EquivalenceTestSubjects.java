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
 * <h2>Which factory, and how the choice is made</h2>
 *
 * <p>{@link EquivalenceTestSubject} offers two factories and the contractual leg decides between
 * them. The discriminant is <b>whether the vector carries an interest leg at all</b>:
 *
 * <ul>
 *   <li>Any {@link FlowKind#INTEREST} or {@link FlowKind#COMBINED_EMI} flow with a non-zero amount
 *       means the instrument returns something other than accretion, and
 *       {@link EquivalenceTestSubject#couponBearingAtPar} is the shape: advanced at par, repayable
 *       at par, with the lifetime interest as the coupon total. {@code COMBINED_EMI} counts because
 *       an annuity vector carries no separate interest flow — the whole instalment is one flow —
 *       and a discriminant that looked only for {@code INTEREST} would read every EMI loan in the
 *       book as a zero-coupon instrument and refuse it Tier 3 on FR-412.
 *   <li>No interest leg means the entire return is the difference between what was paid and what
 *       is redeemed, which is {@link EquivalenceTestSubject#discountInstrument}: a T-bill, CP, CD
 *       or the {@code Case 9} zero-coupon bond.
 * </ul>
 *
 * <p>Deciding on the <em>vector</em> rather than on {@code terms.shape()} is deliberate, and it is
 * the same argument {@code EquivalenceTestSubject}'s own javadoc makes for deriving the return
 * profile from cash amounts instead of reading an {@code is_zero_coupon} flag: "An instrument
 * booked with a coupon code but no coupon flows is caught here; a flag-based test would pass it."
 * A shape of {@code BULLET} whose projector emitted no interest — a zero-rate advance mis-booked as
 * a term loan — is a discount instrument to FR-412 whatever the product master says.
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
     *                     {@link #contractualReceipts}
     * @param proposedTier what the tier assignment proposed, carried through unchanged. The gate
     *                     disposes; this mapper does not second-guess the proposal
     */
    static EquivalenceTestSubject subjectFor(
        OnboardingRequest request, ProjectionResult projection, MaterialityTier proposedTier) {

        FlowVector contractual = projection.contractual();
        String populationId = populationIdFor(request);
        int tenorMonths = originalTenorMonths(request.terms());
        Money advanced = inceptionAmount(request.contractId(), projection);
        Money receipts = contractualReceipts(contractual).abs();

        if (!carriesAnInterestLeg(contractual)) {
            // No interest leg at all: everything the instrument returns is the difference between
            // the price paid and the amount redeemed. This is the Case 9 shape and the T-bill
            // shape, and EquivalenceTestSubject.isZeroCoupon() will read the zero coupon total and
            // FR-412 will refuse Tier 3 outright.
            return EquivalenceTestSubject.discountInstrument(
                populationId, proposedTier, tenorMonths, advanced, receipts);
        }
        Money lifetimeInterest = receipts.minus(advanced);
        if (!lifetimeInterest.isPositive()) {
            // An interest leg is present and the contractual receipts do not exceed what was
            // advanced: an interest-free advance repayable at face, or a schedule whose flows total
            // less than the principal. Neither is a coupon-bearing instrument in FR-412's sense —
            // there is no positive coupon leg to carry the recognition profile — and
            // EquivalenceTestSubject refuses a negative coupon total outright, so it is presented
            // as the discount instrument it arithmetically is. The refusal that follows is the one
            // EquivalenceTestSubject.isZeroCoupon() documents for exactly this case: "an
            // interest-free advance repayable at face has no return to mis-accrete, so refusing it
            // Tier 3 buys nothing except consistency. It is refused anyway, because FR-412 is a
            // refusal and the moment it acquires an exception for 'well, this one is harmless' it
            // becomes a threshold with an undocumented boundary."
            return EquivalenceTestSubject.discountInstrument(
                populationId, proposedTier, tenorMonths, advanced, receipts);
        }
        // Advanced and repayable at par, with the coupon leg alongside. lifetimeInterest is
        // arithmetic and not a judgement: total contractual receipts less the principal advanced,
        // both summed off the vector, both at magnitude. On reference case 1 that is
        // 24 x 47,073.47 = 1,129,763.28 of instalments less 1,000,000.00 advanced = 129,763.28 of
        // lifetime interest, and the accretion share is nil because the exposure redeems at par.
        return EquivalenceTestSubject.couponBearingAtPar(
            populationId, proposedTier, tenorMonths, advanced, lifetimeInterest);
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
     * Everything the contractual leg says will be received, signed as the vector holds it.
     *
     * <p><b>The contractual leg, never the expected one.</b> The expected leg truncates at expected
     * life and carries the balance outstanding as an {@code EXPECTED_PREPAYMENT}, so its totals are
     * a behavioural assumption. FR-412 is a statement about the instrument's contractual return
     * profile — 03 § 10.3's "the entire return is accretion" — and reading the behavioural leg
     * would make a Tier 3 refusal move when the prepayment curve was re-estimated.
     *
     * <p>The kinds are partitioned by {@link #legOf}, an exhaustive switch, so that adding a
     * {@link FlowKind} constant is a compile error here rather than a silent omission. That
     * matters in one direction in particular: a kind quietly dropped from the receipt side
     * understates the coupon total, which understates the accretion share, which lets a
     * deep-discount instrument through FR-412 — the Cambodia direction.
     */
    private static Money contractualReceipts(FlowVector contractual) {
        Money receipts = Money.zero(contractual.currency());
        for (CashFlow flow : contractual.flows()) {
            if (legOf(flow.kind()) == Leg.RECEIPT) {
                receipts = receipts.plus(flow.amount());
            }
        }
        return receipts;
    }

    /**
     * Whether the vector carries a coupon or interest leg — the discriminant between the two
     * factories.
     *
     * <p>Non-zero amounts only. A projector that emitted a schedule of zero interest flows for a
     * zero-rate advance has produced an instrument whose entire return is accretion, and reading the
     * presence of the flow rather than its amount would hand it the Tier 3 shortcut on the strength
     * of a row of zeroes.
     */
    private static boolean carriesAnInterestLeg(FlowVector contractual) {
        for (CashFlow flow : contractual.flows()) {
            boolean interestBearing =
                flow.kind() == FlowKind.INTEREST || flow.kind() == FlowKind.COMBINED_EMI;
            if (interestBearing && !flow.amount().isZero()) {
                return true;
            }
        }
        return false;
    }

    /** Which side of the subject's arithmetic a flow falls on. */
    private enum Leg {
        /** Cash out at inception: the price paid, or the principal advanced. */
        ADVANCE,
        /** A contractual receipt over the life: principal, coupon, balloon, residual. */
        RECEIPT,
        /** An integral fee or cost. Excluded from both sides; see {@link #inceptionAmount}. */
        FEE
    }

    /**
     * The flow kind's side, as an exhaustive switch so a new {@link FlowKind} cannot be forgotten.
     *
     * <p>{@code EXPECTED_PREPAYMENT} is classed as a receipt even though the contractual leg never
     * carries one — it is the contractual balance at a truncation date, so if a projector ever put
     * one on the contractual leg it would be a contractual receipt and not something to drop.
     *
     * <p>{@code NOTIONAL_REDEMPTION} is a receipt for the same reason: the B5.4.4 shortcut treats
     * the balance at the repricing date as a redemption, and that is precisely the redemption
     * amount FR-412 measures accretion against.
     */
    private static Leg legOf(FlowKind kind) {
        return switch (kind) {
            case DISBURSEMENT -> Leg.ADVANCE;
            case PRINCIPAL, INTEREST, COMBINED_EMI, BALLOON, RESIDUAL_VALUE, NOTIONAL_REDEMPTION,
                EXPECTED_PREPAYMENT -> Leg.RECEIPT;
            case INTEGRAL_FEE_RECEIVED, INTEGRAL_COST_PAID -> Leg.FEE;
        };
    }
}
