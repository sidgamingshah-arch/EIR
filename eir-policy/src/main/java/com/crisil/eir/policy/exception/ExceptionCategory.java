package com.crisil.eir.policy.exception;

/**
 * The ten exception-queue categories of
 * <a href="../../../../../../../../../docs/04-data-model.md">04 § 3</a>, which until now
 * existed only as a Markdown list.
 *
 * <p><b>Every category is a hard stop for that contract, never a silent default</b> (FR-905).
 * That is the whole content of the queue: the alternative to raising an exception is not
 * "carry on with a reasonable assumption", it is publishing a wrong rate that nothing
 * downstream can distinguish from a right one. ACPIR forbids manual override (ADR-0008), so
 * quarantining the contract and naming the defect is the only available response to bad input.
 *
 * <p>Note the deliberate asymmetry with invariants. An invariant that fails says <em>this
 * computed figure does not tie</em>; an exception says <em>this contract could not be computed
 * at all, or was computed from an input the engine refuses to trust</em>. {@code IC1_BREACH}
 * sits in both worlds and is listed here because a broken initial carrying amount stops the
 * contract rather than merely flagging it.
 */
public enum ExceptionCategory {

    /**
     * A fee code the rule set does not map. FR-202 — never default to either treatment, because
     * both defaults are wrong in the direction nobody checks: treating an unmapped fee as
     * {@code INTEGRAL} moves it into the carrying amount, and treating it as
     * {@code AS_INCURRED} keeps it out. The classification is the answer, not a fallback.
     */
    UNMAPPED_FEE_CODE(true),

    /**
     * An {@code INTEGRAL} cost posting with no {@code cost_function} attribute. FR-203: ACPIR 53
     * capitalises incentives paid to employees acting as selling agents and excludes internal
     * credit-appraisal cost, and source HR data is structured along neither line. Without the
     * attribute the posting cannot be placed on either side of that boundary.
     */
    MISSING_COST_FUNCTION(true),

    /** The solver found no root. FR-402 — never fall back to the contractual rate. */
    NO_SOLUTION(true),

    /**
     * More than one sign change in the flow vector, so the rate is not unique. Publishing
     * whichever root the iteration happened to land on would be arbitrary.
     */
    MULTIPLE_ROOTS(true),

    /** A field the computation cannot proceed without. */
    MISSING_MANDATORY_FIELD(true),

    /**
     * A penal charge reached the ingestion boundary. RBI's 2023 direction excludes penal
     * charges from the EIR entirely; invariant PC-1 asserts the exclusion positively rather
     * than trusting it.
     */
    PENAL_CHARGE_REJECTED(true),

    /**
     * The initial gross carrying amount is not the net cash flow at inception (invariant IC-1).
     * Either a fee is misclassified or a non-cash item entered the vector.
     */
    IC1_BREACH(true),

    /**
     * A Tier 3 population's equivalence test is out of date (invariant TG-1). Does not stop the
     * contract — it <em>demotes</em> the population to Tier 2 and computes properly, which is
     * more expensive and correct. Spec 03 § 10.2.
     */
    STALE_EQUIVALENCE_TEST(false),

    /** A discontinued hedge with no basis-adjustment amortisation schedule. */
    DISCONTINUED_HEDGE_NO_SCHEDULE(true),

    /**
     * A Tier 2 pool failed its mandatory quarterly back-test against contract-level
     * computation. Forces the pool to contract-level measurement (03 § 10.1) rather than
     * stopping it.
     */
    POOL_BACKTEST_BREACH(false);

    private final boolean stopsTheContract;

    ExceptionCategory(boolean stopsTheContract) {
        this.stopsTheContract = stopsTheContract;
    }

    /**
     * Whether the contract yields no figure at all, as against being measured by a different
     * and more expensive route.
     *
     * <p>Eight of the ten stop the contract. The two that do not —
     * {@link #STALE_EQUIVALENCE_TEST} and {@link #POOL_BACKTEST_BREACH} — are the cases where
     * the specification names the fallback: demote to Tier 2, measure at contract level. Those
     * still raise an exception, because a population quietly switching measurement basis is
     * exactly the kind of thing a close should surface, but they do not quarantine the
     * contract.
     */
    public boolean stopsTheContract() {
        return stopsTheContract;
    }

    /**
     * Whether an unresolved exception of this category blocks the accounting close.
     *
     * <p>04 § 3: "Unresolved exceptions block the close unless explicitly accepted with
     * approval." Every category does, including the two that do not stop their contract — a
     * pool that failed its back-test has moved measurement basis, and closing a period without
     * anyone acknowledging that is the failure this exists to prevent.
     */
    public boolean blocksClose() {
        return true;
    }
}
