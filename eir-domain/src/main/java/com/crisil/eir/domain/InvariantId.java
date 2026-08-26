package com.crisil.eir.domain;

/**
 * The invariants, asserted in production rather than only in tests. A breach
 * raises a control exception and blocks the period close.
 *
 * <p>Identifiers match the calculation specification section 9 and the control
 * set, so a production breach, a failing test and an audit workpaper all name the
 * same thing.
 */
public enum InvariantId {

    /** Initial gross carrying amount equals the net cash flow at inception. */
    IC_1("GCA0 = net cash flow at inception"),

    /** Terminal EIR-leg carrying amount is zero on a full-term, event-free contract. */
    TR_1("terminal EIR-leg GCA = 0"),

    /** Total EIR interest = total contractual interest + net integral fee +/- catch-ups. */
    INV_1("sum EIR interest = sum contractual interest + net fee"),

    /** EIR exceeds contractual iff the net integral fee is income. */
    INV_2("EIR vs contractual ordered by fee sign"),

    /** Total cash received = principal + total contractual interest on billed flows. */
    INV_3("cash received = principal + contractual interest"),

    /** Unamortised fee = contractual carrying amount - EIR carrying amount. */
    INV_4("unamortised fee = leg difference"),

    /** The EIR is unchanged across a catch-up restatement. */
    CU_1("EIR unchanged across catch-up"),

    /** Catch-up = PV(revised flows at original EIR) - carrying amount before. */
    CU_2("catch-up = PV(revised, original EIR) - GCA before"),

    /** Stage 3: net-basis interest + ECL discount unwind = gross-basis interest. */
    ST_2("net interest + ECL unwind = gross interest"),

    /** Stage 3: gross roll-forward, shadow unwind, suspense and recognised income reconcile. */
    S3_1("Stage 3 four-way reconciliation"),

    /** Stage 3: recognised interest income is nil. */
    S3_2("Stage 3 recognised income = 0"),

    /** No posting excluded by Direction entered any EIR stream or the carrying amount. */
    PC_1("penal charge exclusion asserted"),

    /** Pre-floor ECL retained and reported alongside post-floor. */
    PF_1("pre-floor ECL retained"),

    /** The credit-adjusted EIR is unchanged across a cure. */
    POCI_1("credit-adjusted EIR retained on cure"),

    /** No discontinued hedge without an active basis-adjustment amortisation schedule. */
    HB_1("discontinued hedge has amortisation schedule"),

    /** No hedging or swap cost present in any EIR cash flow stream. */
    HB_2("no hedging cost in EIR stream"),

    /** Sub-ledger contract balances sum to the GL control account. */
    SL_1("sub-ledger ties to GL"),

    /** Journal debits equal credits, per run and per contract. */
    SL_2("journals balance"),

    /** A re-run of a closed period reproduces published figures bit-identically. */
    DT_1("deterministic replay"),

    /** Every Tier 3 population has a current equivalence test on file. */
    TG_1("Tier 3 equivalence test in date"),

    // ---- Structure-specific, from the compositional cash-flow model (doc 09 section 7) ----

    /** Scheduled principal over the contractual ladder sums to the principal advanced. */
    ST_3("scheduled principal = principal advanced"),

    /** Capitalised moratorium interest is compound accretion; deferred-simple is simple, and they differ. */
    ST_4("moratorium interest matches its servicing basis"),

    /** A balloon or residual-value ladder amortises to the terminal amount, not to zero. */
    ST_5("balloon ladder amortises to the terminal amount"),

    /** Tranche disbursements sum to notional, each dated on or after the value date. */
    ST_6("tranche draws sum to notional"),

    /** With any option present, at least two exercise policies are computed and the divergence quantified. */
    ST_7("optionality divergence quantified"),

    /** Expected life never exceeds the ECL horizon. */
    ST_8("expected life within the ECL horizon"),

    /**
     * A behavioural or option re-estimation on an instrument with a nil unamortised
     * premium or discount produces a catch-up of exactly zero.
     *
     * <p>The invariant that keeps the close window survivable: without it the engine
     * churns the whole par-priced book on every curve refresh for no P&amp;L effect.
     */
    ST_9("re-estimation at par produces no catch-up"),

    /** Any business-day adjustment or seasonal calendar makes periodic indexing unavailable. */
    ST_10("calendar irregularity forces actual dating"),

    /** An incoherent blueprint is rejected at construction, naming the conflicting dimensions. */
    ST_11("blueprint coherence"),

    /** A conversion option, or any SPPI failure, yields no EIR at all. */
    ST_12("SPPI failure yields no EIR"),

    /**
     * A schedule whose structure implies par pricing does price to par at its own coupon,
     * within the instalment-rounding residue.
     *
     * <p>The control INV-2 stopped providing when its baseline was corrected to subtract
     * the par gap: the gap is now measured and netted, so a schedule that misses par by
     * thousands passes INV-2 on the correct arithmetic. Where the structure says par is
     * expected, that miss is a data error and this is what says so.
     */
    ST_13("a par-priced structure prices to par"),

    /**
     * No policy version is in force without a current portfolio impact preview for the
     * content it will apply.
     *
     * <p>FR-210 calls the preview <em>mandatory</em>, and a mandatory artefact nobody
     * asserts the presence of is an artefact somebody will eventually skip. The risk this
     * guards is quantified in the roadmap's register: a behavioural-curve revision carries
     * 3.73x leverage on year-one fee recognition — the UK restatement pattern — so a version
     * going effective unpreviewed is how that lands with nobody having seen the number.
     *
     * <p>Distinct from a bare existence check. A preview of an earlier draft of the same
     * version id satisfies "a preview exists" and is worse than none, because it reads as
     * diligence; the check is against the draft's content, not its identifier.
     */
    PG_1("no policy version effective without a current impact preview"),

    /**
     * Every date in a period a run reports on resolves to exactly one policy version of
     * each kind the run consulted.
     *
     * <p>The companion to {@link #DT_1}, and separate from it on purpose. DT-1 asks whether
     * a replay reproduces the published figures bit-identically; this asks whether the
     * policy the replay would resolve against still exists and is unambiguous. A period with
     * a date no version governs cannot be replayed at all, which is a different failure from
     * one that replays to different numbers — and {@code InvariantResult.conjunction} keeps
     * only the first breach's deviation among results sharing an id, so publishing both
     * under DT-1 would have made whichever came second uninterpretable.
     */
    PV_1("a policy version resolves for every date in a closed period"),

    /**
     * Every routed event resolves to a routing table version in force on the event's date.
     *
     * <p>ADR-0006 makes the driver-to-mechanism mapping versioned configuration so that an
     * IASB amendment to B5.4.5 is a table change rather than a re-engineering event. That
     * only holds while every event finds a table: a gap in the version series is an event
     * whose treatment is undefined, and the tempting fallback — the compiled-in baseline —
     * would silently reintroduce exactly the hard-coded mapping the ADR exists to remove.
     * Asserted over a whole period ahead of a close, because finding the gap one event at a
     * time finds it after the run has started.
     */
    RT_1("every routed event resolves to a table version in force"),

    /**
     * Every fee code in the rule set has a per-code default in force, so no code depends on
     * a product-and-entity carve-out existing to be classifiable at all.
     *
     * <p>Not a refusal at construction, deliberately: a partially-loaded taxonomy is a real
     * state during the months-long sourcing exercise 08 § 0 calls the programme's critical
     * path, and refusing it would make the gap invisible rather than absent. Reported
     * instead, so the FR-210 approval gate can make it mandatory at the point where
     * "mandatory" means something. The list is the quantified form of "how much of this
     * taxonomy will still raise exceptions", which is what an impact preview is for.
     */
    RS_1("every fee code has a per-code default in force");

    private final String statement;

    InvariantId(String statement) {
        this.statement = statement;
    }

    public String statement() {
        return statement;
    }
}
