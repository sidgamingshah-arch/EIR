package com.crisil.eir.policy.close;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything step 7 of 02 § 3.1 is asked to approve: the period, the attestation, and the evidence
 * from steps 3 to 6.
 *
 * <p><b>The gate consumes this; it computes none of it.</b> The invariant results come from the
 * sweep that produced the figures, the exception records from the queue the run raised them into,
 * the reconciliations from the systems that hold the two sides. FR-901 is an enforcement point —
 * the place where that evidence is read — and a gate that recomputed any of it would be publishing
 * a second answer under an identifier already entitled to one, which
 * {@code InvariantResult.conjunction}'s javadoc records this engine finding three times.
 *
 * <p><b>Every attestation field is nullable, and that is the point.</b> {@code closedBy},
 * {@code closedAt} and {@code versionCutoffAt} are exactly the three columns
 * {@code ck_accounting_period_closure_attested} requires of a closed period, and if this record
 * refused a missing one then {@link CloseGateRefusal#CLOSURE_NOT_ATTESTED} and
 * {@link CloseGateRefusal#MISSING_VERSION_CUTOFF} could never fire — the operator would get a
 * stack trace at step 7 instead of a list of what is missing, and the two refusals would be
 * decoration. The same reasoning {@code JournalEntry} applies to letting an unbalanced entry exist
 * so that SL-2 has something to detect.
 *
 * <p>The lists are copied on construction. A close is evaluated against the evidence <em>as
 * presented</em>: a queue that kept mutating while the gate read it would let a refusal list and
 * the period it describes come apart, and the operator would be shown problems that no longer
 * exist alongside a lock that already happened.
 *
 * @param period            the period to lock; must be {@code CLOSING} for the gate to proceed
 * @param closedBy          who is locking it (FR-902), or null — refused, not thrown
 * @param closedAt          system time of the lock, or null — refused, not thrown
 * @param versionCutoffAt   the {@code recorded_at} boundary a replay must read as at (04 § 5), or
 *                          null — refused, not thrown
 * @param invariantResults  the dashboard of step 4; an empty list is refused, not treated as green
 * @param exceptions        the queue snapshot of step 3
 * @param acceptances       the two-signature acceptances evidencing 04 § 3's "accepted with
 *                          approval" for whichever exceptions were not fixed
 * @param reconciliations   step 6's ties; all four scopes of {@link ReconciliationScope} required
 */
public record CloseRequest(
    AccountingPeriod period,
    String closedBy,
    Instant closedAt,
    Instant versionCutoffAt,
    List<InvariantResult> invariantResults,
    List<ExceptionRecord> exceptions,
    List<ExceptionAcceptance> acceptances,
    List<ReconciliationTie> reconciliations) {

    public CloseRequest {
        Objects.requireNonNull(period, "period");
        invariantResults = List.copyOf(Objects.requireNonNull(invariantResults, "invariantResults"));
        exceptions = List.copyOf(Objects.requireNonNull(exceptions, "exceptions"));
        acceptances = List.copyOf(Objects.requireNonNull(acceptances, "acceptances"));
        reconciliations = List.copyOf(Objects.requireNonNull(reconciliations, "reconciliations"));
    }

    /**
     * The same request, reading the exception evidence from the live queue.
     *
     * <p>The production shape. {@link ExceptionQueue#records()} returns a snapshot taken under the
     * queue's monitor, so what the gate evaluates is the queue as it stood at one instant — which
     * is what a close needs: a partition still raising exceptions while the gate reads would give
     * a decision about a queue that no longer exists.
     */
    public static CloseRequest presenting(
        AccountingPeriod period, String closedBy, Instant closedAt, Instant versionCutoffAt,
        List<InvariantResult> invariantResults, ExceptionQueue queue,
        List<ExceptionAcceptance> acceptances, List<ReconciliationTie> reconciliations) {
        Objects.requireNonNull(queue, "queue");
        return new CloseRequest(period, closedBy, closedAt, versionCutoffAt, invariantResults,
            queue.records(), acceptances, reconciliations);
    }

    /** Whether an attestation field is missing — the {@code CLOSURE_NOT_ATTESTED} condition. */
    public boolean isAttested() {
        return closedBy != null && !closedBy.isBlank() && closedAt != null;
    }

    /** One audit sentence naming the period and the volume of evidence presented. */
    public String describe() {
        return "close of " + period.describe() + " requested by "
            + (closedBy == null || closedBy.isBlank() ? "nobody" : closedBy) + " at " + closedAt
            + " with " + invariantResults.size() + " invariant result(s), " + exceptions.size()
            + " exception(s), " + acceptances.size() + " acceptance(s), "
            + reconciliations.size() + " reconciliation(s)";
    }

    @Override
    public String toString() {
        return describe();
    }
}
