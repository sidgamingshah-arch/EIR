package com.crisil.eir.application.replay;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One period's replay: the artefact to reproduce, and the request a live run of that period would
 * be given.
 *
 * <h2>Why a live {@link RunRequest} is an input</h2>
 *
 * <p>Because 05 § 3.3 says the replay is the <em>same batch job</em>, and the cheapest way to be
 * sure of that is to be handed the job and change one field of it. {@link #shadowRunRequest}
 * copies all five ports, the period and the book straight through and substitutes exactly two
 * things: the run id, because a replay is a distinct run in {@code amortisation_run}, and the
 * {@link AsAtBoundary}, because that is what a replay <em>is</em>.
 *
 * <p>The alternative — a port set assembled here into a request of this package's own design —
 * looks equivalent and is not. It gives the replay its own assembly code, and assembly code is
 * where a divergence hides: a replay that forgot to pass the core banking feed, or that built its
 * boundary from a different period end, is a parallel pipeline with one line of difference and it
 * is still a parallel pipeline. Copying a request the caller also uses for the live run makes the
 * two provably the same job, and {@code ReplayUseCaseTest} asserts component-by-component that
 * nothing but the boundary and the id moved.
 *
 * <p>The template's own {@link AsAtBoundary} is <b>discarded</b>, and nothing is asserted about it.
 * A guard on it would guard nothing: the field is not read. What the replay boundary is built from
 * is the {@link PublishedRun} — the period end for business time, the original run's
 * {@code recorded_at} for system time — which is 05 § 3.3 verbatim.
 *
 * @param published     the run whose figures were published; DT-1's reference
 * @param liveRunTemplate a request for a live run of the same period and book, supplying the ports
 */
public record ReplayRequest(PublishedRun published, RunRequest liveRunTemplate) {

    public ReplayRequest {
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(liveRunTemplate, "liveRunTemplate");
        if (liveRunTemplate.periodId() != published.periodId()) {
            throw new IllegalArgumentException(
                "the template runs period " + liveRunTemplate.periodId()
                    + " and the published run covers " + published.periodId()
                    + "; a replay driven by the wrong period's request would read the wrong"
                    + " contract version set and every figure would be a finding");
        }
        if (!liveRunTemplate.bookId().equals(published.bookId())) {
            throw new IllegalArgumentException(
                "the template runs book " + liveRunTemplate.bookId()
                    + " and the published run covered " + published.bookId()
                    + "; a replay of a different book has a different population");
        }
    }

    /**
     * The as-at boundary a replay of this period carries — 05 § 3.3's two clauses, as one value.
     *
     * <p>Business time is the period <b>end</b> and system time is the original run's
     * {@code recorded_at}. Neither is read from a clock, which is the property ADR-0003 and DT-1
     * rest on: this method is a pure function of the published run, so tonight's replay and next
     * year's audit replay of the same period build the identical boundary.
     *
     * <p>The period end and not {@link com.crisil.eir.policy.replay.ClosedPeriod#closedOn} — that
     * off-by-one has a method of its own upstream, {@code policyResolutionDate()}, and it is worth
     * repeating why: March 2027 closes in April 2027, 1 April is the commonest effective date in
     * the Indian fiscal calendar, and resolving at the close date applies the new fiscal year's
     * rule to a period that ended in the old one.
     */
    public AsAtBoundary replayBoundary() {
        return AsAtBoundary.replaying(
            published.period().policyResolutionDate(), published.recordedAt(), published.runId());
    }

    /**
     * The same job, with a replay boundary and its own run id.
     *
     * <p>Every other component is the identical reference from {@link #liveRunTemplate()}. That is
     * not an optimisation — it is the assertion 05 § 3.3 makes, written as code.
     *
     * @param shadowRunId the replay's own {@code run_id}; its output goes to the shadow table
     *                    rather than over the published figures, and it must differ from the
     *                    published run's id or DT-1 would be comparing a run against itself
     */
    public RunRequest shadowRunRequest(String shadowRunId) {
        Objects.requireNonNull(shadowRunId, "shadowRunId");
        if (shadowRunId.strip().equals(published.runId())) {
            // ReplayComparison refuses a run compared against itself, with the reason that such a
            // comparison cannot fail. Caught here as well because at this point it is still a
            // request: failing before the ten-million-contract job runs costs nothing, and
            // failing after it costs the job.
            throw new IllegalArgumentException(
                "the shadow run would carry the published run's own id " + shadowRunId
                    + "; a run compared against itself is bit-identical to itself and the"
                    + " comparison cannot fail, which reads as DT-1 coverage and is not");
        }
        return new RunRequest(
            shadowRunId,
            liveRunTemplate.periodId(),
            liveRunTemplate.bookId(),
            replayBoundary(),
            liveRunTemplate.contracts(),
            liveRunTemplate.contractState(),
            liveRunTemplate.coreBanking(),
            liveRunTemplate.generalLedger(),
            liveRunTemplate.policy());
    }

    /**
     * The shadow run id for a replay of {@code publishedRunId} on {@code nightOf}.
     *
     * <p>Derived from its two inputs and nothing else. {@code nightOf} is the night control C-12
     * runs, which is an <em>input</em> to the control and not a clock read (03 § 1.1) — so two
     * executions of tonight's job produce the same shadow run id and the shadow table's writes are
     * idempotent, while last night's replay of the same period keeps a distinct id and a distinct
     * row.
     */
    public static String shadowRunId(String publishedRunId, LocalDate nightOf) {
        Objects.requireNonNull(publishedRunId, "publishedRunId");
        Objects.requireNonNull(nightOf, "nightOf");
        return "REPLAY-" + nightOf + "-OF-" + publishedRunId;
    }

    /** The closed period being replayed, {@code YYYYMM}. */
    public int periodId() {
        return published.periodId();
    }
}
