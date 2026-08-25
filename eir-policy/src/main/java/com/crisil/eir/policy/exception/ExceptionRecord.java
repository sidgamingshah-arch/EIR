package com.crisil.eir.policy.exception;

import com.crisil.eir.calc.solver.SolveStatus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry in the exception queue — the {@code EXCEPTION} entity of
 * <a href="../../../../../../../../../docs/04-data-model.md">04 § 3</a>, which until now
 * existed only as a list of column names.
 *
 * <p>The eight components {@code contractId} … {@code resolutionNote} are that entity, in that
 * order. The ninth, {@link #cause}, is not: it is the live throwable that produced the record
 * and it exists because <b>a captured failure with no diagnostic is a contract nobody can
 * fix</b>. FR-905 requires that one malformed contract not fail a ten-million-contract run, and
 * the cheap way to satisfy that requirement is to swallow the throwable and file a category —
 * which converts a run-stopping defect into ten thousand queue entries that say
 * {@code MISSING_MANDATORY_FIELD} and nothing else. Nobody can work that queue. So the
 * throwable travels with the record for as long as the run lives, and {@link #diagnostic()}
 * renders it for whatever the {@code payload_ref} points at.
 *
 * <p><b>Equality is over the eight persisted fields only.</b> The cause is a pointer to a live
 * object with identity equality, so including it would mean two records describing the same
 * queued exception — the same contract, run, category and detail — comparing unequal because
 * the throwables were separate instances. That would break every {@code contains} check a
 * caller makes against the queue, and the queue's own resolve-in-place lookup with it.
 *
 * <p>Records are immutable. Working an exception ({@link #resolve}, {@link #acceptWithApproval})
 * produces a new record rather than mutating this one, which is what lets the queue hold the
 * before-and-after of a resolution if it ever needs to.
 *
 * @param contractId     the contract the failure is attributed to; never blank, because a
 *                       failure nobody can attribute is not isolated per contract at all
 * @param raisedByRunId  the amortisation run that raised it (04 § 2.13)
 * @param category       which of the ten categories of 04 § 3
 * @param detail         what happened, in enough words to act on; never blank
 * @param payloadRef     pointer to the stored input payload — the flow vector for a failed
 *                       solve (03 § 4.3), the contract snapshot otherwise; null where the
 *                       diagnostic is carried by {@code cause} instead
 * @param status         where this sits in its working life
 * @param resolvedBy     who resolved or approved it; null while {@link ExceptionStatus#OPEN}
 * @param resolutionNote why; null while {@code OPEN}
 * @param cause          the throwable captured by {@link FailureIsolation}, or null for an
 *                       exception raised from a status value rather than a throw
 */
public record ExceptionRecord(
    String contractId,
    String raisedByRunId,
    ExceptionCategory category,
    String detail,
    String payloadRef,
    ExceptionStatus status,
    String resolvedBy,
    String resolutionNote,
    Throwable cause) {

    public ExceptionRecord {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(raisedByRunId, "raisedByRunId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(status, "status");
        if (contractId.isBlank()) {
            // FR-905 isolates failures PER CONTRACT. An entry with no contract id cannot be
            // worked, cannot be counted against a population, and cannot be excluded from a
            // close — it is a run-level failure wearing a contract-level record's clothes.
            throw new IllegalArgumentException(
                "an exception queue entry names the contract it isolated; FR-905 is per-contract");
        }
        if (raisedByRunId.isBlank()) {
            throw new IllegalArgumentException(
                "exception on contract " + contractId + " names no run; 04 § 2.13 requires"
                    + " exceptions_raised to tie back to a run");
        }
        if (detail.isBlank()) {
            throw new IllegalArgumentException(
                "exception " + category + " on contract " + contractId + " has no detail; a"
                    + " category alone tells whoever works the queue nothing they can act on");
        }
        // The diagnostic rule, stated once and enforced at construction. Either the payload was
        // stored somewhere a reviewer can fetch it, or the throwable is still attached. Neither
        // means the contract was quarantined and the reason discarded.
        if (isBlank(payloadRef) && cause == null) {
            throw new IllegalArgumentException(
                "exception " + category + " on contract " + contractId + " carries neither a"
                    + " payload reference nor a throwable; a captured failure with no diagnostic"
                    + " is a contract nobody can fix");
        }
        if (status.requiresSignatory()) {
            if (isBlank(resolvedBy)) {
                // The rule quoted depends on which of the two happened. 04 § 3's
                // acceptance-with-approval clause is about closing over a defect that stands;
                // quoting it at somebody who was recording a fix misnames what they did.
                throw new IllegalArgumentException(
                    "exception " + category + " on contract " + contractId + " is " + status
                        + " with nobody named; "
                        + (status == ExceptionStatus.ACCEPTED_WITH_APPROVAL
                            ? "04 § 3 unblocks a close only on explicit acceptance with approval,"
                                + " and an anonymous approval is not one"
                            : "a fix nobody signed cannot be evidence that the input was"
                                + " corrected, and the next run will raise it again"));
            }
            if (isBlank(resolutionNote)) {
                throw new IllegalArgumentException(
                    "exception " + category + " on contract " + contractId + " is " + status
                        + " with no note; the note is the whole audit trail of why a close was"
                        + " allowed to proceed over this contract");
            }
        } else if (!isBlank(resolvedBy) || !isBlank(resolutionNote)) {
            // An OPEN record carrying a resolver is a half-applied resolution. It reads as
            // unworked in every report and as worked to anyone who looks at the columns.
            throw new IllegalArgumentException(
                "exception " + category + " on contract " + contractId + " is OPEN but names a"
                    + " resolver or a note; resolution and status move together");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * A newly raised exception, {@link ExceptionStatus#OPEN}, with the input payload stored.
     *
     * <p>The route for a failure that is a <em>value</em> rather than a throw — a
     * {@link SolveStatus} of {@code NO_SOLUTION}, a fee code the rule set does not map. There is
     * no throwable to attach, so {@code payloadRef} is mandatory here: 03 § 4.3 requires a
     * failed solve to reach the queue "with the flow vector attached", and a solve failure
     * without its vector cannot be reproduced.
     */
    public static ExceptionRecord raise(
        String contractId, String runId, ExceptionCategory category, String detail,
        String payloadRef) {
        if (isBlank(payloadRef)) {
            throw new IllegalArgumentException(
                "exception " + category + " on contract " + contractId + " was raised from a"
                    + " status rather than a throw, so the stored payload is the only diagnostic"
                    + " there will ever be; 03 § 4.3 requires it to be attached");
        }
        return new ExceptionRecord(
            contractId, runId, category, detail, payloadRef, ExceptionStatus.OPEN, null, null,
            null);
    }

    /**
     * An exception captured from a throwable by {@link FailureIsolation}, {@code OPEN}, with the
     * throwable retained.
     *
     * <p>{@code payloadRef} is null: the payload store is written by the persistence layer, and
     * at the moment of capture the throwable is the diagnostic. See {@link #withPayloadRef} for
     * the hand-off.
     */
    public static ExceptionRecord captured(
        String contractId, String runId, ExceptionCategory category, String detail,
        Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        return new ExceptionRecord(
            contractId, runId, category, detail, null, ExceptionStatus.OPEN, null, null, cause);
    }

    /**
     * The exception a solve outcome raises, or empty where it raises none — <b>the production
     * consumer of {@link SolveStatus#routesToExceptionQueue()}</b>.
     *
     * <p>{@code SolveStatus} was built so that a failed solve is a value that travels with the
     * computation rather than a silent fallback to zero or to the contractual rate (03 § 4.3,
     * the most damaging defect available to this engine). Until this method it was a value that
     * travelled nowhere: nothing in production asked the question. Here is where the answer is
     * consumed, and the mapping is 04 § 3's own two solver categories —
     * {@code NO_SOLUTION → }{@link ExceptionCategory#NO_SOLUTION},
     * {@code MULTIPLE_ROOTS → }{@link ExceptionCategory#MULTIPLE_ROOTS}.
     *
     * <p>An {@link Optional} rather than a nullable record or a thrown exception, because the
     * two statuses that do not route are not errors and must not be handled as if they were.
     * {@code SOLVED} is the ordinary outcome. {@code REQUIRES_REVIEW} carries a usable rate and
     * routes for <em>approval</em> (03 § 4.4(2)) — a different workflow entirely, and filing it
     * as an exception would block closes on contracts whose figures are computed and usable.
     *
     * @param detail what the solve was over, for the queue entry; the status is appended
     * @param payloadRef where the flow vector was stored (03 § 4.3 requires it)
     */
    public static Optional<ExceptionRecord> ofSolve(
        String contractId, String runId, SolveStatus status, String detail, String payloadRef) {
        Objects.requireNonNull(status, "status");
        if (!status.routesToExceptionQueue()) {
            return Optional.empty();
        }
        return Optional.of(raise(
            contractId, runId, categoryOf(status), "solve ended " + status + ": " + detail,
            payloadRef));
    }

    /**
     * The queue category for a solve status that routes to the queue.
     *
     * <p>Total over the two routing statuses and deliberately loud on the others: asking for the
     * category of a {@code SOLVED} solve is a caller that has skipped
     * {@link SolveStatus#routesToExceptionQueue()}, and answering with a guess would file a
     * successful computation as a failure.
     */
    public static ExceptionCategory categoryOf(SolveStatus status) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case NO_SOLUTION -> ExceptionCategory.NO_SOLUTION;
            case MULTIPLE_ROOTS -> ExceptionCategory.MULTIPLE_ROOTS;
            case SOLVED, REQUIRES_REVIEW -> throw new IllegalArgumentException(
                "solve status " + status + " does not route to the exception queue"
                    + (status.requiresApproval()
                        ? "; REQUIRES_REVIEW carries a usable rate and routes for approval"
                            + " (03 § 4.4(2)), which is a different workflow"
                        : ""));
        };
    }

    /**
     * Whether the contract this exception was raised against yields no figure at all.
     *
     * <p>Delegates to {@link ExceptionCategory#stopsTheContract()} and does not second-guess it.
     * Eight of the ten categories stop the contract; {@code STALE_EQUIVALENCE_TEST} and
     * {@code POOL_BACKTEST_BREACH} instead demote the population to a more expensive and correct
     * measurement, so a contract carrying one of those two is still measured and still reported.
     */
    public boolean stopsTheContract() {
        return category.stopsTheContract();
    }

    /**
     * Whether this entry, as it stands, stops the accounting close (04 § 3).
     *
     * <p>Both terms are asked. The status decides whether the entry has been worked; the
     * category decides whether an entry of this kind ever blocked. Today every category answers
     * yes — including the two that do not stop their contract, because a population that has
     * quietly moved measurement basis is exactly what a close should surface — but the term is
     * kept so that the category remains the authority on its own semantics.
     */
    public boolean blocksClose() {
        return status.blocksClose() && category.blocksClose();
    }

    /**
     * Whether this entry quarantines its contract right now.
     *
     * <p>Two conditions, and the second is the one that is easy to get wrong.
     * {@link ExceptionStatus#ACCEPTED_WITH_APPROVAL} unblocks the close but does <b>not</b> lift
     * the quarantine: nothing about the malformed input changed, so the contract still has no
     * computed figure. Treating acceptance as a release would put the contract back into the
     * reported population with whatever stale or absent figure it had, which is the precise
     * failure the queue exists to prevent.
     */
    public boolean quarantinesContract() {
        return category.stopsTheContract() && !status.defectFixed();
    }

    /**
     * Whether this entry demoted its contract to a more expensive measurement rather than
     * stopping it.
     *
     * <p>The complement of {@link #quarantinesContract()} on the same two conditions, so that no
     * caller has to reconstruct the pair. A resolved demotion is no longer a demotion: once the
     * equivalence test is refreshed or the pool passes its back-test, the population goes back to
     * the tier its assignment claims.
     */
    public boolean demotesContract() {
        return !category.stopsTheContract() && !status.defectFixed();
    }

    /** Whether this entry has not been worked yet. */
    public boolean isOpen() {
        return status == ExceptionStatus.OPEN;
    }

    /**
     * The same exception, with the defect recorded as <em>fixed</em>.
     *
     * <p>Distinct from {@link #acceptWithApproval}: resolution asserts that a recomputation will
     * now succeed, so it lifts the contract's quarantine. Claiming resolution for a defect that
     * still stands puts a contract back into the reported population with no figure behind it.
     */
    public ExceptionRecord resolve(String resolvedBy, String resolutionNote) {
        return new ExceptionRecord(
            contractId, raisedByRunId, category, detail, payloadRef, ExceptionStatus.RESOLVED,
            resolvedBy, resolutionNote, cause);
    }

    /**
     * The same exception, signed off so the close may proceed over it — 04 § 3's "explicitly
     * accepted with approval".
     *
     * <p>The defect is <b>not</b> fixed. The approver is accepting a close that omits this
     * contract's figure, and the next run will raise the same exception on the same input. The
     * approver's name and reason are mandatory (enforced at construction) because this is the
     * only place in the process where a person's decision stands between a known defect and a
     * published set of accounts.
     */
    public ExceptionRecord acceptWithApproval(String approvedBy, String approvalNote) {
        return new ExceptionRecord(
            contractId, raisedByRunId, category, detail, payloadRef,
            ExceptionStatus.ACCEPTED_WITH_APPROVAL, approvedBy, approvalNote, cause);
    }

    /**
     * The same exception with the payload store's reference filled in.
     *
     * <p>The hand-off from {@link #captured}: the throwable is the diagnostic at the moment of
     * capture, and the persistence layer writes the payload afterwards and comes back with a
     * pointer. The throwable is kept, not dropped — the stack is the part that says which stage
     * failed, and the stored payload is the part that says on what.
     */
    public ExceptionRecord withPayloadRef(String newPayloadRef) {
        if (isBlank(newPayloadRef)) {
            throw new IllegalArgumentException(
                "payload reference for contract " + contractId + " is blank");
        }
        return new ExceptionRecord(
            contractId, raisedByRunId, category, detail, newPayloadRef, status, resolvedBy,
            resolutionNote, cause);
    }

    /**
     * The captured throwable rendered as text, stack trace included, or an empty
     * {@link Optional} where this exception came from a status value rather than a throw.
     *
     * <p>What gets written to the payload store. The stack trace is not decoration: it names the
     * stage that failed, and for a category as coarse as {@code MISSING_MANDATORY_FIELD} it is
     * the difference between a contract somebody can fix and a queue entry somebody closes over.
     */
    public Optional<String> diagnostic() {
        if (cause == null) {
            return Optional.empty();
        }
        StringWriter rendered = new StringWriter();
        try (PrintWriter into = new PrintWriter(rendered)) {
            cause.printStackTrace(into);
        }
        return Optional.of(rendered.toString());
    }

    /** A one-line audit sentence naming the contract, the category and where the entry stands. */
    public String describe() {
        return category + " on contract " + contractId + " (run " + raisedByRunId + "): " + detail
            + " [" + status
            + (status == ExceptionStatus.OPEN ? "" : " by " + resolvedBy + " — " + resolutionNote)
            + (quarantinesContract() ? ", contract quarantined" : ", contract still measured")
            + (blocksClose() ? ", BLOCKS CLOSE" : "") + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // Eight fields, not nine. See the class javadoc: the cause has identity equality, and
        // two records describing the same queued exception must compare equal regardless of
        // which throwable instance produced them.
        return other instanceof ExceptionRecord that
            && contractId.equals(that.contractId)
            && raisedByRunId.equals(that.raisedByRunId)
            && category == that.category
            && detail.equals(that.detail)
            && Objects.equals(payloadRef, that.payloadRef)
            && status == that.status
            && Objects.equals(resolvedBy, that.resolvedBy)
            && Objects.equals(resolutionNote, that.resolutionNote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            contractId, raisedByRunId, category, detail, payloadRef, status, resolvedBy,
            resolutionNote);
    }

    @Override
    public String toString() {
        return describe();
    }
}
