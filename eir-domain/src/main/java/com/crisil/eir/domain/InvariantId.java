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

    /**
     * Stage 3: gross roll-forward, shadow unwind, suspense ledger and recognised income
     * reconcile, every period (FR-605, 03 § 7.3).
     *
     * <p><b>Four legs, one result.</b> The quantities come from independent sources — the
     * carrying-amount ledger, the EIR accrual, and the suspense ledger — and the control is that
     * they agree:
     *
     * <ol>
     *   <li>closing GCA = opening GCA + the EIR accrual − cash applied;</li>
     *   <li>closing suspense = opening suspense + charged − recovered − written off;</li>
     *   <li>what was charged to suspense is what was billed and not recognised;</li>
     *   <li>cash applied to interest is exactly what came out of suspense.</li>
     * </ol>
     *
     * <p>Published as <em>one</em> result covering all four, whose deviation is the total
     * absolute residual and whose detail names each failing leg. Not four results under one id:
     * {@link InvariantResult#conjunction} keeps only the first breach's deviation among results
     * sharing an id, so four would report one residual and silently drop three — and a
     * reconciliation that reports one of its four breaks is worse than one that reports none,
     * because it looks like it has been read.
     *
     * <p>This id previously carried four unrelated claims, none of them the reconciliation above:
     * a two-way split of billed interest that the lines constructing it made tautological, the
     * cure no-catch-up assertion (now {@link #CR_1}), and the two halves of staging-is-not-an-EIR
     * -event (now {@link #SG_1} and {@link #SG_2}). One of those four carried a <em>rate</em>
     * deviation and the rest carried money, under a single id.
     */
    S3_1("Stage 3 four-way reconciliation"),

    /** Stage 3: recognised interest income is nil. */
    S3_2("Stage 3 recognised income = 0"),

    /** No posting excluded by Direction entered any EIR stream or the carrying amount. */
    PC_1("penal charge exclusion asserted"),

    /**
     * The pre-floor, EIR-derived ECL survives the application of the prudential floor and is
     * reported alongside the post-floor figure (FR-609, ACPIR 90, 03 § 7.5).
     *
     * <p>The ordering the requirement states is: compute the accounting number at the EIR, apply
     * the floor, report <em>both</em>. The failure it forbids is the natural implementation —
     * compute, floor, store one number — after which the accounting figure the EIR produced no
     * longer exists anywhere and the divergence between measurement and reporting cannot be
     * quantified, disclosed, or reconciled in a later period.
     *
     * <p>Asserted as an equality on the way through rather than as a claim about a field: the
     * figure handed in comes back unchanged, and the reported figure is the greater of it and the
     * floor. A floor that lowered the reported provision would not be a floor, and the deviation
     * is the money amount by which the reported figure sits away from where it belongs.
     */
    PF_1("pre-floor ECL retained"),

    /**
     * A Stage 3 exposure is floored at account level, never on a portfolio basis (ACPIR 90,
     * 03 § 7.5).
     *
     * <p>ACPIR 90 applies the prudential floor per product category on a portfolio basis for
     * Stages 1 and 2 and <b>mandatorily at account level for Stage 3</b>. A Stage 3 exposure
     * floored in a portfolio pool has its shortfall averaged against exposures that have no
     * shortfall, which understates the floor on exactly the accounts where it binds hardest.
     *
     * <p>Separate from {@link #PF_1} because it is a different failure with a different remedy:
     * PF-1 breaks when the pre-floor number is lost, and is fixed by retaining it; this breaks
     * when the number was computed the wrong way, and is fixed by recomputing it. The deviation
     * is the provision floored on the wrong basis, which is the exposure a close has to restate.
     */
    PF_2("Stage 3 floored at account level, not portfolio"),

    /** The credit-adjusted EIR is unchanged across a cure. */
    POCI_1("credit-adjusted EIR retained on cure"),

    /**
     * A stage migration left the EIR alone (FR-610, 03 § 7.4).
     *
     * <p>Staging is not an EIR event. The deviation is a <b>rate</b> — the periodic difference —
     * which is why this cannot share an id with {@link #SG_2} below, whose deviation is a money
     * amount, and why neither could stay under {@link #S3_1}, whose deviation is a residual in
     * currency. A rate breach of 0.0001 and a balance breach of 0.0001 are not comparable
     * quantities, and a close that sums or sorts deviations across them is reading noise.
     *
     * <p>Kept as an explicit assertion rather than trusted, because a pipeline routing staging
     * through a general event handler can reach the same code path as a reset and pick up a
     * re-solve on the way past. That is the failure this exists to catch: the rate moving because
     * of how the event was dispatched, not because anything decided it should.
     */
    SG_1("staging left the EIR unchanged"),

    /**
     * A stage migration left the gross carrying amount alone (FR-610, 03 § 7.4).
     *
     * <p>The balance half of the same requirement, separated for the reason given on
     * {@link #SG_1}: this deviation is money. The failure it catches is a staged-down balance —
     * net of allowance — leaking into the carrying-amount ledger, which would then roll forward
     * on the wrong base for the rest of the contract's life and never re-converge.
     */
    SG_2("staging left the gross carrying amount unchanged"),

    /**
     * Recognition resumes prospectively on cure, with no catch-up (FR-607, 03 § 7.4).
     *
     * <p>The cure period recognises exactly its own gross-basis interest and nothing more.
     * Booking a catch-up would recognise income that was correctly never recognised — the
     * suppression was the right answer at the time, so reversing it later restates a period that
     * was not wrong.
     *
     * <p>Asserted rather than assumed because the catch-up is the tempting implementation. The
     * suspense balance is sitting there, the borrower has cured, and crediting it to income
     * reads as the account being made whole. It is not: the balance stays on the suspense ledger
     * until it is recovered in cash or written off, and neither of those is a period-of-cure
     * event.
     */
    CR_1("cure recognises prospectively, with no catch-up"),

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
    RS_1("every fee code has a per-code default in force"),

    /**
     * No exposure has income suspended except under a pool definition in force on the date
     * (FR-608, 03 § 7.4).
     *
     * <p>Account-level suspension analysis is impractical for credit cards and KCC at volume, and
     * ACPIR provides no portfolio carve-out — so one has to come from policy, and 03 § 7.4 makes
     * the pool definition the approved artefact that carries it. This is the control on that
     * sentence: suspending income is suppressing recognised revenue, and the only thing standing
     * between a pooled suspension and an unapproved one is whether an approved definition covers
     * the exposure on the date.
     *
     * <p>The deviation is the count of exposures suspended without cover. A count rather than the
     * income suppressed, because the remedy is per exposure — each one either belongs in an
     * approved pool or has to be analysed individually — and because the suppressed income is
     * already reported, exposure by exposure, under S3-2.
     */
    PL_1("no exposure suspended except under a pool definition in force"),

    /**
     * Only portfolio-managed products are suspended at pool level (FR-608, 03 § 7.4).
     *
     * <p>The carve-out exists because account-level analysis is impractical for cards and KCC. It
     * is not a general licence to suspend by pool, and the abuse it invites is precisely the one
     * worth a control: pooling a book of term loans, where account-level analysis is entirely
     * practical, to avoid doing it. Eligibility is read off
     * {@code TierAssignmentFeature.CARD_OR_KCC_REVOLVER} rather than a second product list,
     * because "is this a portfolio-managed revolver" is a question this codebase already answers
     * and two answers to it would eventually disagree.
     *
     * <p>Deviation is the count of pools covering ineligible products, not of exposures: the
     * remedy is to dissolve the pool, and a pool of a million card accounts and a pool of ten
     * term loans are one finding each.
     */
    PL_2("only portfolio-managed products are suspended at pool level"),

    /**
     * No transition fair value relies on the ACPIR 19 paragraph 19 presumption without a rebuttal
     * evidence reference (FR-908, 04 § 6).
     *
     * <p>ACPIR 19 permits carrying cost to be taken as the best evidence of fair value. It is a
     * <em>presumption</em>, and applying it requires evidence — a file built across FY27 rather
     * than assembled at the transition date, which is why 08 Phase 4 makes this the phase's exit
     * gate. Applied to a whole legacy book with no file behind it, the day-1 valuation becomes
     * "we kept the numbers we had" wearing the language of a fair value measurement, and the
     * difference to opening retained earnings comes out at nil because nothing was measured.
     *
     * <p>Deviation is the count of contracts claiming the presumption with no evidence named. A
     * count because the remedy is per contract — each one needs a reference filed or a different
     * technique — and because there is no money size: the exposures are exactly the ones whose
     * fair value nobody has established.
     */
    TF_1("no paragraph 19 presumption without rebuttal evidence"),

    /**
     * Legacy migration is prioritised by survival past 31 March 2030, not by size (FR-908,
     * 08 Phase 4).
     *
     * <p>Reconstructing an EIR for a loan maturing in 2029 is wasted effort: ACPIR 21 and 50
     * require the legacy book on the EIR by 31 March 2030, and an exposure that has run off by
     * then never needs a reconstructed rate. The failure this catches is the natural
     * prioritisation — largest cohorts first — which spends the scarce reconstruction capacity on
     * balances that will have gone.
     *
     * <p>Deviation is the count of cohorts surviving the deadline that are queued behind a cohort
     * that does not. A count, because the remedy is a re-ordering.
     */
    LC_1("legacy cohorts prioritised by survival, not size"),

    /**
     * ACPIR 21 and ACPIR 50 are tracked as two obligations, not one (04 § 6).
     *
     * <p>They share a deadline and they are not the same requirement: the loan must come under
     * the EIR regime (21), and its ECL discounting must migrate from the interim contractual rate
     * to the EIR (50). A contract can be on the EIR for interest recognition while its ECL is
     * still discounted at the contractual rate, and that gap is invisible to anything reading a
     * single migration flag — which is why 04 § 6 gives the discount basis its own table rather
     * than a column on the contract.
     *
     * <p><b>The invariant is that both are tracked, not that both are finished.</b> This
     * distinction took a second pass to get right. The obvious formulation — fail while any
     * contract is still on the interim basis — makes the control fail continuously from 2027 to
     * 2030, and a breach blocks the close (03 § 9), so it would block every close for three years
     * while describing a state ACPIR 50 explicitly permits. A control that is red by design is a
     * control that gets suppressed, and then it is not there for the year it matters.
     *
     * <p>So the breach is one of two things, both of which are genuine failures on the day they
     * occur: a contract whose ECL discount basis is <em>not recorded at all</em>, which is the
     * gap being invisible rather than open; or a contract still on the interim basis <em>after
     * 31 March 2030</em>, when the concession has expired. Deviation is the count of contracts in
     * either state.
     *
     * <p>The size of the remaining migration is published as plain data rather than as a
     * deviation, because a shrinking number is what a programme tracks and a control is not the
     * place to put it. 08's warning applies to the reading, not the arithmetic: ACPIR 50's
     * concession buys time on ECL discounting and must not be read as a general deferral.
     */
    TM_1("ACPIR 21 and ACPIR 50 migration tracked separately"),

    /**
     * Every cohort measured on a deemed EIR has an approved derivation on file (FR-909, 04 § 6).
     *
     * <p>A deemed rate is what the engine uses where full reconstruction of the original flows was
     * not feasible. That is a legitimate answer at legacy scale, and it is also the answer that
     * hides an unwillingness to look — so the derivation has to say why reconstruction failed and
     * how the rate was arrived at instead, and somebody other than its preparer has to have
     * approved it. The reason the approval is not optional: a deemed rate <em>recognises income
     * on an assumption</em>, every period, for the rest of the exposure's life.
     *
     * <p>Deviation is the count of cohorts on a deemed basis with no approved derivation. Reported
     * rather than refused at construction, because a prepared-and-unapproved derivation is the
     * normal state of one in flight; what the control catches is a cohort being <em>measured</em>
     * on a rate nobody signed.
     */
    DE_1("every deemed EIR has an approved derivation"),

    /**
     * No day-1 below-market difference is taken to a destination without an approved policy
     * position behind it (FR-909, reference § 5 item 11, reference § 4 Silence 6).
     *
     * <p>ACPIR 19 and 20 require fair value at initial recognition and say <em>nothing</em> about
     * what to do with the resulting day-1 difference. The reference register carries that as
     * Silence 6 at {@code [MED-HIGH]}, and it is not theoretical: for a public sector bank the
     * staff housing book is large enough that the adjustment is material. The reference's own
     * position on staff loans is that the shortfall is employee compensation and not a lending
     * loss — but that is a reading, and where the standard is silent the entity closes it by
     * Board-approved policy.
     *
     * <p>So the destination is data, not a default, and this is the assertion that it was chosen
     * rather than assumed. A difference booked to an operating expense because that is where the
     * code happened to send it is the failure: it is a policy decision taken by an implementation
     * detail, on a figure large enough to move a reported result.
     *
     * <p>Deviation is the count of originations whose destination is unnamed or rests on a policy
     * version not in force on the origination date. Reported rather than refused, because an
     * unresolved position is the real state of a silence still with the ACPIR 57 sub-committee.
     */
    BM_1("no day-1 below-market difference without an approved destination"),

    /**
     * A closed period is never mutated; a correction is a restatement artefact (FR-902).
     *
     * <p>The requirement is unusual in this set because it is about <em>absence of change</em>
     * rather than about a figure. 04 § 5's bitemporality is what makes it satisfiable: a
     * correction records a new version in system time and leaves business time alone, so the
     * period still replays to what it published (DT-1) and the restatement is a separate,
     * dated fact.
     *
     * <p>The failure it catches is the one that looks like diligence. Somebody finds an error in
     * a closed period and fixes it — in place, because that is what fixing means everywhere
     * else — and the period now reproduces figures nobody ever reported. DT-1 would not
     * necessarily notice, because a replay of the corrected data is internally consistent; what
     * is lost is the correspondence between what was published and what the ledger says was
     * published.
     *
     * <p>Deviation is the count of mutated rows or figures found in a closed period.
     */
    CL_1("a closed period is never mutated"),

    /**
     * The contractual interest leg ties to the core banking system, with zero unexplained
     * difference (FR-804, control C-14).
     *
     * <p>The cheapest external check on the engine that exists, and worth more than it looks for
     * the same reason C-15 is: it compares against a number computed by a different system for a
     * different purpose. The engine's contractual leg is what the borrower was billed, and the
     * CBS is the book of record for exactly that (ADR-0004) — so a difference is not a matter of
     * interpretation. Either the engine mis-projected the schedule or the feed is wrong, and both
     * need finding before the EIR leg built on top of it is believed.
     *
     * <p>"Zero unexplained" rather than zero: a timing difference with a stated cause is
     * explained and does not breach. The deviation is the money amount that is not.
     */
    RC_1("contractual leg ties to core banking"),

    /**
     * A published movement schedule's columns sum: opening + EIR interest − cash applied = closing,
     * per product and in total (FR-805).
     *
     * <p><b>Why the movement schedule needs its own id rather than borrowing one.</b> The report is
     * a presentation of figures other invariants already govern, so the temptation is to publish its
     * columns check under SL-2 or ST-2. Two reasons not to. First, they are different claims: SL-2
     * is a journal's two sides, ST-2 is the net-interest decomposition against the ledger, and this
     * is a rendering's arithmetic — a schedule can foot perfectly on figures a broken journal
     * produced, and a correct journal can be rendered into a schedule that does not add up.
     * Second, and decisively: {@link InvariantResult#conjunction} keeps only the <em>first</em>
     * breach's deviation among results sharing an id, so a movement break and a journal break filed
     * under one id would report one deviation and silently drop the other.
     *
     * <p><b>What makes it fail, which is the standing requirement on any new control here.</b> A
     * computed contract present in the run but absent from the schedule's rows: its opening and
     * closing balances leave the totals while its interest stays, so the total column stops footing
     * by that contract's roll-forward. That is the defect a per-product report actually has — a
     * product bucket that silently drops a contract with no product id on file — and it is why
     * the check reports four legs rather than one boolean.
     *
     * <p>Deviation is the money residue by which the columns fail to foot, presented at the
     * schedule's own scale. Rounding is not a breach: the presented figures are each rounded to the
     * minor unit, so four rounded figures can disagree by up to two paise per contract without
     * anything being wrong, and the evaluator carries that bound.
     */
    MV_1("movement schedule columns sum");

    private final String statement;

    InvariantId(String statement) {
        this.statement = statement;
    }

    public String statement() {
        return statement;
    }
}
