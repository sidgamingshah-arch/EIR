package com.crisil.eir.policy.replay;

/**
 * What kind of thing a replay got wrong — the diagnosis, not the size.
 *
 * <p>Every one of these is a DT-1 breach and they are kept distinct because the remedies are
 * unrelated, and because the deviation cannot tell them apart: DT-1's deviation here is a count
 * (see {@link ReplayComparison}), so the detail is the only place the diagnosis can live.
 *
 * <p>Ordered from "the figures moved" to "the rule moved", which is also roughly the order of
 * increasing seriousness. A value difference is a defect somebody will find. A policy version
 * difference on figures that all matched is a defect nobody will find, because everything
 * downstream of it agrees.
 */
public enum DiscrepancyKind {

    /**
     * Same amount of money, different published figure — {@code 1.0} against {@code 1.00}.
     *
     * <p>The reason this package exists. {@code Money.equals} reports a match, every numeric
     * invariant in the engine passes, and the byte comparison control C-12 performs fails. There
     * is no money size to report: the difference is exactly zero rupees and is still a difference
     * in what was published.
     *
     * <p>Diagnosis is almost always a rounding point that moved.
     * {@code Money.atPresentationScale} is "the only place a money value loses precision, and it
     * is called once per persisted figure" — so a figure reduced in a different frame, or not
     * reduced because the value was already round, or passed through a
     * {@code stripTrailingZeros()} added to tidy a log line, comes out here.
     */
    SCALE_ONLY(false, "same value, different published scale"),

    /** The amount itself moved. The ordinary defect, and the one a reader expects. */
    VALUE_DIFFERS(false, "the amount moved"),

    /**
     * The replay produced the figure in a different currency.
     *
     * <p>Reported rather than thrown, unlike {@code InvariantResult.ofMoney}, which raises
     * {@link IllegalArgumentException} on a currency mismatch because it would have to
     * <em>subtract</em> across currencies to produce a deviation. Nothing is subtracted here — the
     * deviation is a count — so a currency mismatch is a perfectly reportable finding, and
     * reporting it beats aborting the nightly sweep on the first multi-currency book.
     */
    CURRENCY_DIFFERS(false, "the currency moved"),

    /** The close published this figure and the replay did not produce it. */
    MISSING_FROM_REPLAY(false, "published, not reproduced"),

    /**
     * The replay produced a figure the close never published.
     *
     * <p>Counted, and worth counting. It is the shape a replay takes when it runs a wider
     * population than the close did — a contract admitted by today's eligibility rule that the
     * period's own rule excluded — and it is invisible to any check that iterates the published
     * set and looks each figure up.
     */
    ABSENT_FROM_PUBLICATION(false, "reproduced, never published"),

    /**
     * The replay resolved a different version of this policy kind than the close cited.
     *
     * <p>The failure FR-903's second half exists for, and a DT-1 breach <b>even when every figure
     * matches</b>. Reproducing the right number from the wrong rule is luck; next period it will
     * not hold, and the period after that the divergence will be attributed to whatever changed
     * most recently.
     *
     * <p>The concrete case: a replay harness that resolves policy by
     * {@code registry.inForceOn(kind, LocalDate.now())} instead of at the period end. It works
     * perfectly until the first supersession, then reproduces a closed period under a rule written
     * after it closed.
     */
    POLICY_VERSION_DIFFERS(true, "the replay resolved a different policy version"),

    /** The close cited a version of this kind and the replay cited none. */
    POLICY_VERSION_MISSING_FROM_REPLAY(true, "policy kind consulted at close, not on replay"),

    /**
     * The replay consulted a policy kind the close did not.
     *
     * <p>A replay reading a rule the original run never opened is not reproducing that run, even
     * if the extra rule happened to change nothing.
     */
    POLICY_VERSION_ABSENT_FROM_PUBLICATION(true, "policy kind consulted on replay, not at close"),

    /**
     * The version the replay used is not the one the registry resolves for the period end.
     *
     * <p>Distinct from {@link #POLICY_VERSION_DIFFERS}, which compares the replay against the
     * <em>record</em> of what the close cited. This compares it against the <em>timeline</em>, and
     * catches the case where the record itself is wrong: both runs cite the same id and that id
     * was not in force for the period. Then the period cannot be replayed under the policy then in
     * force at all — the figures reproduce, and they reproduce a run that was governed by the
     * wrong rule.
     *
     * <p>Also fires where the registry no longer holds the version at all, which is the
     * precondition {@code PolicyVersionRegistry.policyResolvableOn} publishes under PV-1. Named
     * here too, because a replay whose policy has been dropped from the registry has to fail
     * something, and DT-1 is the control that was asked to run.
     */
    POLICY_NOT_IN_FORCE_AT_PERIOD_END(true, "the version used was not in force for the period"),

    /**
     * A policy kind the registry says governed the period, which <em>neither</em> run cited.
     *
     * <p>Added because without it the policy half of FR-903 degraded to a no-op that reported a
     * pass. The comparison iterated only the kinds at least one run had stamped, so two runs
     * carrying no stamps at all compared nothing and returned green — against a registry that did
     * hold a version in force for the period. "Under the policy then in force is not an optional
     * half of the requirement" was in the class javadoc while nothing forced the runs to give it
     * anything to check.
     *
     * <p>It is the one policy finding the <em>timeline</em> raises rather than the two runs, which
     * is what makes the registry argument load-bearing instead of decorative: a kind in force that
     * nobody consulted is a period reproduced without reference to a rule that governed it, and
     * neither run can notice its own omission.
     */
    POLICY_KIND_NOT_CONSULTED(true, "a kind in force for the period was cited by neither run");

    private final boolean policy;
    private final String statement;

    DiscrepancyKind(boolean policy, String statement) {
        this.policy = policy;
        this.statement = statement;
    }

    /**
     * Whether this is a policy-resolution finding rather than a figure finding.
     *
     * <p>Used to keep the two counts separately reportable in the detail. They are added into one
     * deviation — see {@link ReplayComparison#dtOne()} — and a reader still needs to know which
     * half of FR-903 broke, because "the numbers moved" and "the rule moved" send the
     * investigation to different places.
     */
    public boolean isPolicyFinding() {
        return policy;
    }

    public String statement() {
        return statement;
    }
}
