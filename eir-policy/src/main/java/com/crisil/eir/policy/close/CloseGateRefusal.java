package com.crisil.eir.policy.close;

/**
 * Why the period-close gate refused to lock a period — FR-901's refusal vocabulary.
 *
 * <p><b>This enum is the shape FR-901 takes, and the shape it deliberately does not take.</b>
 * FR-901 is "enforce a period close workflow with hard gates: all invariants green, all
 * exceptions cleared or accepted with approval, all reconciliations tied". That is an
 * <em>enforcement point</em> — the place where invariant results, exception statuses and
 * reconciliation residuals are read — and not a claim about a figure. So it gets a decision with
 * reasons, in the style of {@link com.crisil.eir.policy.approval.TransitionRefusal}, and it gets
 * no invariant identifier of its own. "The close gate held" is not an invariant: an id for it
 * would be a control whose only input is the gate's own output, which cannot fail on anything a
 * ledger could contain, and this engine has already found four such ids and treats them as worse
 * than absent controls because they read as coverage.
 *
 * <p><b>A vocabulary rather than a message</b> for the reason {@code TransitionRefusal} gives:
 * refusals are counted and acted on. A close that fails on nine red invariants and one
 * self-approved exception acceptance needs those two routed to two different people, without
 * either of them pattern-matching on English. The particulars — which invariant, which contract,
 * which residual — live in {@link CloseRefusal#detail()}.
 *
 * <p><b>Reasons that overlap are kept apart on purpose.</b> {@link #UNEVIDENCED_ACCEPTANCE} and
 * {@link #SELF_APPROVED_ACCEPTANCE} are both "this exception was not properly accepted", and
 * {@link #RECONCILIATION_NOT_PRESENTED} and {@link #RECONCILIATION_NOT_TIED} are both "this
 * reconciliation does not tie". In each pair the second is a break somebody has looked at and the
 * first is a break nobody has, and those go to different desks.
 */
public enum CloseGateRefusal {

    /**
     * The period is not {@code CLOSING}.
     *
     * <p>An {@code OPEN} period is still taking postings, so its figures can move underneath the
     * invariant results the gate was handed: a dashboard green at step 4 says nothing about the
     * book at step 7. The close runs from {@code CLOSING} (02 § 3.1), which is what makes the
     * evidence and the book the same book.
     */
    PERIOD_NOT_IN_CLOSING("a close runs from CLOSING; an OPEN period is still taking postings"),

    /**
     * The period is already {@code CLOSED}.
     *
     * <p>Refused rather than treated as a satisfied no-op, and it is FR-902 rather than tidiness
     * that makes it so. {@code closed_by}, {@code closed_at} and {@code version_cutoff_at} are one
     * row's three columns; a second close overwrites all three, so the record of who locked the
     * period and which version horizon a replay must read is replaced by the second attempt's —
     * and every figure still ties.
     */
    PERIOD_ALREADY_CLOSED("CLOSED is terminal (FR-902); a correction is a restatement artefact"),

    /**
     * The request names no closer, or no close instant.
     *
     * <p>{@code ck_accounting_period_closure_attested} in gate form. The database would refuse the
     * row, which is exactly why it is refused here too: an operator who reaches step 7 with no
     * signatory should be told at step 7, not by a constraint violation after the run has
     * committed everything else.
     */
    CLOSURE_NOT_ATTESTED("FR-902 requires a closed period to name who closed it and when"),

    /**
     * No system-time cutoff was supplied.
     *
     * <p>Named separately from {@link #CLOSURE_NOT_ATTESTED} because it is the one attestation
     * field nobody misses. Without it, a replay of this period has no {@code recorded_at} boundary
     * and reads today's version set instead of the period's — 04 § 5 — so the period closes
     * looking complete and stops being replayable (DT-1) at the moment the next policy version is
     * recorded. The DDL comment says the same thing in fewer words: "as at the close" is otherwise
     * a wall-clock guess.
     */
    MISSING_VERSION_CUTOFF("a closed period names the system-time boundary a replay reads as at"),

    /**
     * The cutoff is later than the close itself.
     *
     * <p>A cutoff after {@code closedAt} lets a replay read versions recorded after the period was
     * locked, which is how a closed period comes to reproduce figures that were never published —
     * the CL-1 failure, arriving through the replay path rather than through an edit.
     */
    VERSION_CUTOFF_AFTER_CLOSE("the replay boundary cannot be later than the close"),

    /**
     * The close instant falls before the close itself began.
     *
     * <p>A refusal rather than the {@link IllegalStateException} {@link AccountingPeriod} would
     * raise on the same condition, and the reason it exists at all is that
     * <b>no data condition may reach that throw through the gate</b>. The gate's contract is that
     * a close presenting many problems reports all of them; a caller whose close timestamp came
     * from a different clock than its {@code closing_started_at} would otherwise get a stack trace
     * instead of the other refusals, and the operator would fix a clock and then discover the six
     * real problems one release later.
     */
    CLOSE_PREDATES_ITS_OWN_START("a close cannot be stamped before the close began"),

    /**
     * The close instant falls before the period has ended anywhere on earth.
     *
     * <p>Closing March before March is over locks the period against the postings it has not
     * received yet. Compared conservatively — the gate refuses only where no clock in any real
     * jurisdiction could place the instant inside the period — because a false refusal on a
     * timezone boundary is how a hard gate gets argued down to a soft one.
     */
    CLOSE_PREDATES_PERIOD_END("a period cannot be locked before the last day it reports on"),

    /**
     * No invariant results were presented.
     *
     * <p><b>The reason this gate is not a tautology.</b> Step 4 of 02 § 3.1 is "review the
     * invariant dashboard. Any red blocks the close" — and a gate that read "no red" off an empty
     * list would pass a close where the sweep never ran, which is the one case where nothing is
     * known about any figure at all. An empty dashboard is not a green one.
     */
    NO_INVARIANT_RESULTS("an empty invariant dashboard is not a green one (02 § 3.1 step 4)"),

    /**
     * At least one invariant is red.
     *
     * <p>FR-901's first limb, and the gate consumes the results rather than computing them: the
     * invariants are asserted by the engine that produced the figures, each with its own
     * deviation, and re-deriving any of them here would produce a second answer under an
     * identifier that is entitled to one.
     */
    INVARIANT_BREACH("any red invariant blocks the close (FR-901)"),

    /**
     * A queued exception is neither {@code RESOLVED} nor accepted.
     *
     * <p>04 § 3: "Unresolved exceptions block the close unless explicitly accepted with
     * approval." Every one of the ten categories answers
     * {@link com.crisil.eir.policy.exception.ExceptionCategory#blocksClose()} true, so acceptance
     * is the only way past one — which is what makes acceptance, and not the category list, the
     * control worth guarding.
     */
    EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED(
        "an exception is neither cleared nor accepted with approval (04 § 3)"),

    /**
     * An acceptance is on file but the queue row still reads {@code OPEN}.
     *
     * <p>Refused rather than resolved in the acceptance's favour. The queue is what every other
     * reader consults — the exclusion step, the run record's {@code exceptions_raised}, the
     * control report — and a close that proceeded on an artefact the queue does not reflect would
     * publish accounts while the queue reports a live blocker on the same contract.
     */
    ACCEPTANCE_NOT_RECORDED_ON_THE_QUEUE(
        "the queue still reports the exception as unworked; the acceptance is not on the row"),

    /**
     * The queue row says {@code ACCEPTED_WITH_APPROVAL} and no acceptance artefact evidences it.
     *
     * <p><b>The interesting half of FR-901.</b> {@code EXCEPTION.resolved_by} is one column and
     * holds one name, so the row can record that somebody signed but cannot record that
     * <em>two</em> people were involved — and 04 § 3's "accepted with approval" is an approval,
     * which by definition is somebody else's. The row alone is a self-approval that cannot be
     * distinguished from a proper one, so the gate requires the artefact that names both parties.
     */
    UNEVIDENCED_ACCEPTANCE(
        "an accepted exception carries one name; the approval needs the second (04 § 3)"),

    /**
     * The acceptance was approved by the person who put it forward.
     *
     * <p>An exception accepted by the person who raised it is not accepted. Compared with
     * {@link com.crisil.eir.domain.FourEyes#isSelfApproval} — the same identity comparison the
     * policy-version gate and the routing-table version use, so that {@code "Ops.Lead"} and
     * {@code " ops.lead "} are one person here as they are everywhere else.
     */
    SELF_APPROVED_ACCEPTANCE(
        "maker and approver of the acceptance are the same identity; that is not an approval"),

    /**
     * The acceptance names a different approver than the queue row does.
     *
     * <p>The mirror of {@code TransitionRefusal.CHECKER_CONFLICT}, refused for the same reason:
     * both readings need a human. Either the artefact was filed against the wrong exception, or
     * two people believe they signed for the same acceptance and only one name is stored.
     */
    ACCEPTANCE_SIGNATORY_CONFLICT(
        "the queue row and the acceptance name different approvers"),

    /**
     * An acceptance covers no exception this close needs accepting.
     *
     * <p>Refused rather than ignored. An acceptance list assembled against a different run's queue
     * is the shape a re-run takes when somebody carries the approvals forward, and the gate would
     * then be reading one period's approvals as authority to close over another's problems.
     */
    ACCEPTANCE_WITHOUT_AN_EXCEPTION(
        "an acceptance was presented for an exception this period's queue does not hold"),

    /**
     * One of the reconciliations 02 § 3.1 step 6 names was not presented at all.
     *
     * <p>The reconciliation counterpart of {@link #NO_INVARIANT_RESULTS}, and the same defect:
     * "all reconciliations tied" read over whichever reconciliations happened to be handed in
     * passes a close where the Stage 3 four-way was never run. The four required scopes are
     * enumerated in {@link ReconciliationScope}, from step 6's own list.
     */
    RECONCILIATION_NOT_PRESENTED(
        "a reconciliation step 6 requires was not presented; absent is not tied"),

    /**
     * A presented reconciliation has a non-zero residual.
     *
     * <p>FR-901's third limb. The residual is compared at presentation scale, reduced once rather
     * than as two rounded operands, for the reason {@code InvariantResult.ofMoney} sets out at
     * length: rounding both sides first manufactures one-paise breaks on figures that agree.
     */
    RECONCILIATION_NOT_TIED("a reconciliation does not tie (FR-901)");

    private final String explanation;

    CloseGateRefusal(String explanation) {
        this.explanation = explanation;
    }

    /**
     * Why, in general terms, a close is refused for this reason.
     *
     * <p>The general statement, not the particulars — {@link CloseRefusal#detail()} names the
     * invariant, the contract or the residual.
     */
    public String explanation() {
        return explanation;
    }

    /**
     * Whether this refusal describes evidence that exists and does not hold, as against evidence
     * that is missing.
     *
     * <p>The escalation differs, and the distinction is the one
     * {@code ActivationRefusalReason.looksLikeDiligence} draws for the impact preview. A missing
     * reconciliation is a step nobody performed: visible on any checklist, and whoever owns the
     * checklist owns the fix. An untied one, or an exception accepted by its own maker, is a step
     * somebody performed and filed — it satisfies a checkbox control, it reads in the audit file
     * as diligence, and it takes a second person's judgement to overturn.
     */
    public boolean looksLikeDiligence() {
        return switch (this) {
            case RECONCILIATION_NOT_TIED, SELF_APPROVED_ACCEPTANCE, ACCEPTANCE_SIGNATORY_CONFLICT,
                 ACCEPTANCE_NOT_RECORDED_ON_THE_QUEUE, UNEVIDENCED_ACCEPTANCE,
                 VERSION_CUTOFF_AFTER_CLOSE, INVARIANT_BREACH -> true;
            case PERIOD_NOT_IN_CLOSING, PERIOD_ALREADY_CLOSED, CLOSURE_NOT_ATTESTED,
                 MISSING_VERSION_CUTOFF, CLOSE_PREDATES_PERIOD_END, CLOSE_PREDATES_ITS_OWN_START,
                 NO_INVARIANT_RESULTS,
                 EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED, ACCEPTANCE_WITHOUT_AN_EXCEPTION,
                 RECONCILIATION_NOT_PRESENTED -> false;
        };
    }
}
