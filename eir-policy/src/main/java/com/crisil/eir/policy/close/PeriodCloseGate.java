package com.crisil.eir.policy.close;

import com.crisil.eir.domain.AnywhereOnEarth;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The only legal way an {@link AccountingPeriod} reaches {@code CLOSED} — FR-901's hard gates.
 *
 * <p>FR-901 verbatim: "Enforce a period close workflow with hard gates: all invariants green, all
 * exceptions cleared or accepted with approval, all reconciliations tied." 02 § 3.1 walks the same
 * close as an operator does, in seven steps; this class is steps 4, 6 and 7 — the invariant
 * dashboard, the reconciliations, and the approve-and-lock that makes the period immutable
 * (FR-902).
 *
 * <p><b>What this gate is, and what it is not.</b> It is an <em>enforcement point</em>: it reads
 * evidence other subsystems produced and decides whether the period may be locked. It computes no
 * figure, asserts no invariant and publishes no invariant identifier. There is no id for "the close
 * gate held", and {@link CloseDecision} deliberately has no {@code asInvariantResult()} — see the
 * paragraph there for why such an id would be a control that cannot fail.
 *
 * <p><b>Refusals are values, and the whole list comes back.</b> Nothing here throws for a data
 * condition. A close presents a red S3-1, forty unworked exceptions and a sub-ledger break at the
 * same time, worked by three different desks; a gate that threw on the first would turn a day's
 * parallel work into a week of serial re-runs, each costing a full amortisation pass. Throwing is
 * reserved for a gate defect — a null argument.
 *
 * <p><b>Where the interesting half is.</b> Not in the invariant leg, which is a scan for red. It is
 * in "exceptions cleared <em>or accepted with approval</em>":
 * {@link com.crisil.eir.policy.exception.ExceptionCategory#blocksClose()} is true for every one of
 * 04 § 3's ten categories, so acceptance is the only route past a queued exception — which makes
 * acceptance the control, and a self-approved acceptance the way past every other control in this
 * package. Hence {@link ExceptionAcceptance}, which carries both signatures, and hence the
 * comparison being {@link com.crisil.eir.domain.FourEyes#isSelfApproval} rather than a fourth
 * hand-written spelling of the same rule.
 *
 * <p><b>Two absences are refusals, not passes.</b> An empty invariant dashboard is not a green one
 * ({@link CloseGateRefusal#NO_INVARIANT_RESULTS}), and a reconciliation nobody presented is not a
 * tied one ({@link CloseGateRefusal#RECONCILIATION_NOT_PRESENTED}). Both are the shape a
 * hard gate takes when it is written as "no evidence of failure" instead of "evidence of success",
 * and both would pass the single worst case — the close where the step never ran.
 */
public final class PeriodCloseGate {

    /**
     * The first real-world clock to enter a calendar date: UTC+14.
     *
     * <p>Used for one comparison — is the close instant unambiguously earlier than the end of the
     * period it locks — and used in the direction that produces no false refusals. The period's end
     * date has ended <em>somewhere</em> from this offset's midnight onward, so a close at or after
     * it is arguable and only a close before it is refused. Assuming UTC would be wrong in the
     * expensive direction for a bank at UTC+05:30: a legitimate close in the small hours of 1 April
     * IST is 20:30 UTC on 31 March, and a false refusal on a timezone boundary is how a hard gate
     * gets argued down to a soft one.
     *
    private PeriodCloseGate() {
    }

    /**
     * The gate: one request in, one permitted-or-refused answer out, carrying every refusal.
     *
     * <p><b>The order of the legs is the order of 02 § 3.1</b>, so that a refusal list reads down
     * the operator's own checklist: the period and its attestation first (step 7's preconditions),
     * then the invariant dashboard (step 4), then the exception queue (step 3), then the
     * reconciliations (step 6). Nothing short-circuits within the legs.
     *
     * <p><b>A status refusal returns alone</b>, and that is the one deliberate exception to
     * reporting everything. If the period is {@code OPEN} or already {@code CLOSED}, the evidence
     * is not what needs fixing — the period the caller is pointing at is — and a list of thirty
     * evidence refusals underneath would send three desks to work figures that were never in
     * question.
     */
    public static CloseDecision evaluate(CloseRequest request) {
        Objects.requireNonNull(request, "request");
        AccountingPeriod period = request.period();

        // (1) The period itself. See above for why this returns alone.
        if (period.isClosed()) {
            return CloseDecision.refuse(List.of(new CloseRefusal(
                CloseGateRefusal.PERIOD_ALREADY_CLOSED,
                period.describe() + "; a second close would overwrite closed_by, closed_at and"
                    + " version_cutoff_at, so the record of who locked it and which version"
                    + " horizon a replay must read would become the second attempt's")));
        }
        if (!period.status().isCloseable()) {
            return CloseDecision.refuse(List.of(new CloseRefusal(
                CloseGateRefusal.PERIOD_NOT_IN_CLOSING,
                period.describe() + "; move it to CLOSING first, or the figures behind the"
                    + " invariant results presented here can still move")));
        }

        List<CloseRefusal> refusals = new ArrayList<>();
        appendAttestationRefusals(request, refusals);
        appendInvariantRefusals(request, refusals);
        appendExceptionRefusals(request, refusals);
        appendReconciliationRefusals(request, refusals);

        if (!refusals.isEmpty()) {
            return CloseDecision.refuse(refusals);
        }
        // Step 7. attestedClose is package-private precisely so that this is the only route.
        return CloseDecision.permit(period.attestedClose(
            request.closedBy(), request.closedAt(), request.versionCutoffAt()));
    }

    /**
     * {@code ck_accounting_period_closure_attested}, plus the two things the DDL cannot say about
     * the cutoff.
     */
    private static void appendAttestationRefusals(
        CloseRequest request, List<CloseRefusal> refusals) {
        AccountingPeriod period = request.period();
        if (!request.isAttested()) {
            refusals.add(new CloseRefusal(CloseGateRefusal.CLOSURE_NOT_ATTESTED,
                "period " + period.periodId() + " would be locked by "
                    + (request.closedBy() == null || request.closedBy().isBlank()
                        ? "nobody" : "'" + request.closedBy() + "'")
                    + " at " + request.closedAt()
                    + "; FR-902 requires both, and the database refuses the row without them"));
        }
        if (request.versionCutoffAt() == null) {
            refusals.add(new CloseRefusal(CloseGateRefusal.MISSING_VERSION_CUTOFF,
                "period " + period.periodId() + " names no version_cutoff_at, so a replay would"
                    + " read today's version set instead of this period's (04 § 5) and DT-1 would"
                    + " start failing the moment the next policy version is recorded"));
        } else if (request.closedAt() != null
            && request.versionCutoffAt().isAfter(request.closedAt())) {
            refusals.add(new CloseRefusal(CloseGateRefusal.VERSION_CUTOFF_AFTER_CLOSE,
                "cutoff " + request.versionCutoffAt() + " is later than the close "
                    + request.closedAt() + ", so a replay of period " + period.periodId()
                    + " would read versions recorded after the period was locked"));
        }
        if (request.closedAt() != null && period.closingStartedAt() != null
            && request.closedAt().isBefore(period.closingStartedAt())) {
            // Checked here so that it cannot reach AccountingPeriod's constructor, which throws on
            // the same condition. The gate must not throw for a data condition: an operator whose
            // close timestamp came off a different clock than closing_started_at is entitled to
            // see the other refusals in the same list.
            refusals.add(new CloseRefusal(CloseGateRefusal.CLOSE_PREDATES_ITS_OWN_START,
                "close instant " + request.closedAt() + " precedes the start of the close "
                    + period.closingStartedAt() + " on period " + period.periodId()));
        }
        if (request.closedAt() != null) {
            // Conservative: only where no real-world clock could place the instant inside the
            // The widening lives in eir-domain.AnywhereOnEarth, shared with the activation gate.
            // It was duplicated here as a local UTC+14 constant with a comment saying it belonged
            // there; two statements of one rule is this codebase's recurring defect, and the
            // conservative range must not drift between the gate that admits a policy version and
            // the gate that closes a period.
            Instant endedSomewhere =
                AnywhereOnEarth.earliestInstantOf(period.periodEndDate().plusDays(1));
            if (request.closedAt().isBefore(endedSomewhere)) {
                refusals.add(new CloseRefusal(CloseGateRefusal.CLOSE_PREDATES_PERIOD_END,
                    "close instant " + request.closedAt() + " is before period "
                        + period.periodId() + " has ended on any clock on earth (earliest "
                        + endedSomewhere + "), so it would lock the period against postings it"
                        + " has not received yet"));
            }
        }
    }

    /**
     * Step 4: "review the invariant dashboard. Any red blocks the close (FR-901)."
     *
     * <p><b>The results are collapsed to one per invariant before they are read.</b>
     * {@code InvariantResult.oneResultPerInvariant} conjoins each id's results, and the conjunction
     * is false if any leg is — so a dashboard presenting S3-1 twice, passing on one projection and
     * failing on another, blocks the close. Reading the raw list would work too, but collapsing
     * first means the refusal list carries one line per invariant rather than one per assertion,
     * and the deviation reported is the conjunction's own. That ordering matters because
     * conjunction keeps only the first breach's deviation among results sharing an id — a defect
     * this engine records finding three times — so the collapse is where a disagreement becomes
     * visible instead of being resolved by list position.
     */
    private static void appendInvariantRefusals(
        CloseRequest request, List<CloseRefusal> refusals) {
        if (request.invariantResults().isEmpty()) {
            refusals.add(new CloseRefusal(CloseGateRefusal.NO_INVARIANT_RESULTS,
                "period " + request.period().periodId() + " presented no invariant results; the"
                    + " sweep either did not run or its results were not carried to step 7, and"
                    + " in neither case is anything known about any figure"));
            return;
        }
        for (InvariantResult result
            : InvariantResult.oneResultPerInvariant(request.invariantResults())) {
            if (!result.satisfied()) {
                refusals.add(new CloseRefusal(CloseGateRefusal.INVARIANT_BREACH,
                    result.id() + " (" + result.id().statement() + ") is red, deviation "
                        + result.deviation() + ": " + result.detail()));
            }
        }
    }

    /**
     * Step 3, read at step 7: 04 § 3's "unresolved exceptions block the close unless explicitly
     * accepted with approval".
     *
     * <p>Three states, three questions. A {@code RESOLVED} row is cleared — the defect was fixed
     * and a recomputation will produce a figure. An {@code OPEN} row blocks. An
     * {@code ACCEPTED_WITH_APPROVAL} row needs the second signature the row itself cannot hold,
     * because {@code resolved_by} is one column: see {@link ExceptionAcceptance}.
     */
    private static void appendExceptionRefusals(
        CloseRequest request, List<CloseRefusal> refusals) {
        List<ExceptionAcceptance> acceptances = request.acceptances();
        for (ExceptionRecord record : request.exceptions()) {
            List<ExceptionAcceptance> matching = acceptances.stream()
                .filter(acceptance -> acceptance.covers(record))
                .toList();
            switch (record.status()) {
                case RESOLVED -> {
                    // Cleared. Nothing to check: the claim is that the input was corrected, and
                    // the next run will either produce a figure or raise the exception again —
                    // which is a fact about the next run, not evidence this close can weigh.
                }
                case OPEN -> {
                    // The category is asked rather than assumed. Every one of the ten answers
                    // blocksClose() true today, and this defers to the category so that it stays
                    // the authority on its own semantics.
                    if (record.blocksClose() && matching.isEmpty()) {
                        refusals.add(new CloseRefusal(
                            CloseGateRefusal.EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED,
                            record.describe()));
                    } else if (record.blocksClose()) {
                        refusals.add(new CloseRefusal(
                            CloseGateRefusal.ACCEPTANCE_NOT_RECORDED_ON_THE_QUEUE,
                            "acceptance on file — " + matching.get(0).describe() + " — but the"
                                + " queue row is still OPEN: " + record.describe()
                                + "; every other reader of the queue (the exclusion step, the run"
                                + " record, the control report) sees a live blocker"));
                    }
                }
                case ACCEPTED_WITH_APPROVAL -> appendAcceptedRefusals(record, matching, refusals);
                default -> throw new IllegalStateException(
                    "unhandled exception status " + record.status() + "; a close that cannot"
                        + " classify a queue row cannot say whether it blocks");
            }
        }
        appendUnmatchedAcceptanceRefusals(request, refusals);
    }

    /**
     * An {@code ACCEPTED_WITH_APPROVAL} row, checked against the artefact that has to evidence it.
     *
     * <p>Every matching acceptance is examined, not just the first that works. Two acceptances for
     * one exception of which one is self-approved is refused even though the other is sound: only
     * one signatory is stored on the row, so which of the two the audit file will show is decided
     * by nothing an auditor can see.
     */
    private static void appendAcceptedRefusals(
        ExceptionRecord record, List<ExceptionAcceptance> matching, List<CloseRefusal> refusals) {
        if (matching.isEmpty()) {
            refusals.add(new CloseRefusal(CloseGateRefusal.UNEVIDENCED_ACCEPTANCE,
                record.describe() + "; the row names '" + record.resolvedBy() + "' and nothing"
                    + " else, so a proper approval and a self-approval are indistinguishable"));
            return;
        }
        for (ExceptionAcceptance acceptance : matching) {
            if (acceptance.isSelfApproved()) {
                refusals.add(new CloseRefusal(CloseGateRefusal.SELF_APPROVED_ACCEPTANCE,
                    acceptance.describe() + "; an exception accepted by the person who put it"
                        + " forward is not accepted (04 § 3)"));
            } else if (!acceptance.approvedBySignatory(record.resolvedBy())) {
                refusals.add(new CloseRefusal(CloseGateRefusal.ACCEPTANCE_SIGNATORY_CONFLICT,
                    "the queue row names '" + record.resolvedBy() + "' and the acceptance is"
                        + " approved by '" + acceptance.approvedBy() + "': "
                        + acceptance.describe()));
            }
        }
    }

    /**
     * Acceptances that cover nothing this close needed accepting.
     *
     * <p>Refused rather than ignored. An acceptance list carried forward from a previous run is the
     * shape this takes in practice, and the gate would then be reading one period's approvals as
     * authority to close over another's problems. An acceptance covering only {@code RESOLVED} rows
     * is caught here too: the defect was fixed, so nobody needed to sign for closing over it, and
     * the signature on file is evidence of a decision that was not taken.
     */
    private static void appendUnmatchedAcceptanceRefusals(
        CloseRequest request, List<CloseRefusal> refusals) {
        for (ExceptionAcceptance acceptance : request.acceptances()) {
            boolean coversSomethingUnfixed = request.exceptions().stream()
                .anyMatch(record -> acceptance.covers(record) && !record.status().defectFixed());
            if (!coversSomethingUnfixed) {
                boolean coversFixed = request.exceptions().stream()
                    .anyMatch(acceptance::covers);
                refusals.add(new CloseRefusal(CloseGateRefusal.ACCEPTANCE_WITHOUT_AN_EXCEPTION,
                    acceptance.describe() + "; "
                        + (coversFixed
                            ? "the exception it names was RESOLVED, so nobody needed to sign for"
                                + " closing over it"
                            : "this period's queue holds no such exception — an acceptance list"
                                + " carried over from another run cannot authorise this close")));
            }
        }
    }

    /**
     * Step 6: "confirm reconciliations: sub-ledger to GL, contractual leg to CBS, Stage 3 four-way,
     * pre- and post-floor."
     *
     * <p>Presence is checked before residuals, because the two failures are different work. A scope
     * may be presented more than once — one tie per book, say — and every tie is checked; nothing
     * short-circuits, so a close with breaks in three of the four scopes reports three.
     */
    private static void appendReconciliationRefusals(
        CloseRequest request, List<CloseRefusal> refusals) {
        Map<ReconciliationScope, List<ReconciliationTie>> presented =
            new EnumMap<>(ReconciliationScope.class);
        for (ReconciliationTie tie : request.reconciliations()) {
            presented.computeIfAbsent(tie.scope(), scope -> new ArrayList<>()).add(tie);
        }
        for (ReconciliationScope scope : ReconciliationScope.values()) {
            if (scope.requiredForClose() && !presented.containsKey(scope)) {
                refusals.add(new CloseRefusal(CloseGateRefusal.RECONCILIATION_NOT_PRESENTED,
                    "period " + request.period().periodId() + " presented no " + scope.label()
                        + " reconciliation (step 6); it is asserted by " + scope.assertedBy()
                        + ", and absent is not tied"));
            }
        }
        for (ReconciliationTie tie : request.reconciliations()) {
            if (!tie.isTied()) {
                refusals.add(new CloseRefusal(CloseGateRefusal.RECONCILIATION_NOT_TIED,
                    tie.describe()));
            }
        }
    }
}
