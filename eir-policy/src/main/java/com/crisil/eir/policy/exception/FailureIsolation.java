package com.crisil.eir.policy.exception;

import com.crisil.eir.calc.projection.UnsupportedScheduleShapeException;
import com.crisil.eir.calc.projection.blueprint.OptionalitySppiFailureException;
import com.crisil.eir.calc.projection.blueprint.OptionalityUnresolvedException;
import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The per-contract fault barrier of FR-905: "Isolate failures <b>per contract</b>: one malformed
 * contract must not fail a ten-million-contract run."
 *
 * <p>This is the piece that makes a close survivable. Ten million contracts arrive from source
 * systems that were never built to feed an EIR engine, and some non-trivial number of them will
 * be malformed in ways nobody anticipated. Without a barrier the first of those ends the run,
 * and the operational consequence is a close that never completes — so the pressure becomes to
 * patch the data until the run finishes, which is precisely the manual intervention ACPIR
 * forbids (ADR-0008). With a barrier the run completes, the malformed contracts are named, and
 * the close gates on the count (05 § 4.5).
 *
 * <p><b>What is captured, and what is not.</b> The barrier catches
 * {@link RuntimeException} and files it. It does not catch {@link Error}, and that asymmetry is
 * the whole safety argument:
 *
 * <ul>
 *   <li>A {@code RuntimeException} from a projector, a blueprint or an amortisation roll-forward
 *       is a statement about <em>one contract's data</em>. The other 9,999,999 contracts are
 *       unaffected, so quarantining this one and carrying on is correct.
 *   <li>An {@code Error} — {@code OutOfMemoryError}, {@code StackOverflowError} — is a statement
 *       about <em>the process</em>. Nothing about the next contract is more likely to succeed,
 *       and a run that continues past an OOM produces figures assembled from a heap that ran out
 *       partway through. Those figures reconcile to nothing and nobody should trust them. So an
 *       {@code Error} propagates and takes the run with it, which is the outcome that leaves the
 *       accounts unpublished rather than published and wrong.
 * </ul>
 *
 * <p>{@code AssertionError} falls on the {@code Error} side of that line and therefore fails the
 * run. Deliberate: an assertion that fired means the engine's own stated preconditions are
 * broken, which is a code defect and not a fact about the contract, and assertions are disabled
 * in production anyway.
 *
 * <p><b>A second thing is never captured: a run-level invariant breach.</b> 05 § 4.5 draws the
 * line explicitly — "a <em>contract-level</em> failure is isolated; an <em>aggregate
 * invariant</em> failure (SL-1, PF-1, HB-1) fails the run, because it means the whole set is
 * untrustworthy". A sub-ledger that does not tie to the general ledger is not one contract's
 * problem, and filing it as one contract's exception would hide a broken close behind a queue
 * entry. See {@link #RUN_LEVEL_INVARIANTS}.
 *
 * <p><b>And the throwable is never discarded.</b> The cheap way to satisfy FR-905 is to catch,
 * file a category and move on, which yields ten thousand queue entries reading
 * {@code MISSING_MANDATORY_FIELD} with no indication of which field, which stage or which line
 * of code. Nobody can work that queue, so nothing gets fixed, so the same exceptions are
 * accepted-with-approval every period until the acceptance is a rubber stamp. Every record this
 * class produces carries its throwable ({@link ExceptionRecord#cause()},
 * {@link ExceptionRecord#diagnostic()}).
 */
public final class FailureIsolation {

    /**
     * The invariants whose breach condemns the whole run rather than one contract (05 § 4.5).
     *
     * <p>Three, and each earns its place by being a claim about a <em>set</em>:
     *
     * <ul>
     *   <li>{@code SL-1} — sub-ledger contract balances sum to the GL control account. Named in
     *       05 § 4.5. A breach means the sum is wrong; which contract caused it is unknown, and
     *       attributing it to whichever contract happened to be in hand when the sweep ran would
     *       be a fiction.
     *   <li>{@code PF-1} — pre-floor ECL retained alongside post-floor. Named in 05 § 4.5. The
     *       retention is a property of the reported population.
     *   <li>{@code DT-1} — a re-run of a closed period reproduces published figures
     *       bit-identically (FR-903). A replay mismatch is a statement about the run by
     *       construction: the run is the thing that failed to reproduce.
     * </ul>
     *
     * <p><b>HB-1 is deliberately absent</b>, despite 05 § 4.5 listing it. 04 § 3 gives it its own
     * per-contract queue category, {@code DISCONTINUED_HEDGE_NO_SCHEDULE}, and the two documents
     * are reconcilable: a discontinued hedge with no amortisation schedule <em>is</em>
     * attributable to one hedge relationship, so it is isolable and gets named. 05 § 4.5's
     * listing is about the population sweep over all hedge relationships, which does not run
     * under a per-contract barrier in the first place. Where the breach can be pinned on a
     * contract, quarantining that contract loses nothing and lets the close proceed on the rest.
     *
     * <p>SL-2 and HB-2 are absent for the same reason: InvariantId itself states SL-2 "per run
     * <em>and per contract</em>", and a per-contract journal imbalance is exactly what the
     * barrier is for.
     */
    public static final Set<InvariantId> RUN_LEVEL_INVARIANTS =
        Set.copyOf(EnumSet.of(InvariantId.SL_1, InvariantId.PF_1, InvariantId.DT_1));

    /**
     * The category an unrecognised per-contract {@code RuntimeException} is filed under when the
     * caller names no better one.
     *
     * <p>Public and named because it is a policy choice worth arguing with, not an
     * implementation detail. The ten categories of 04 § 3 are a closed set, and they do not
     * contain a general "the engine could not compute this contract" bucket. Something has to
     * happen to a {@code NullPointerException} thrown eight frames deep in a projector on a
     * contract whose tenor field arrived empty, and there are only two candidates:
     *
     * <ul>
     *   <li>Propagate, on the grounds that a category the engine cannot justify is a category it
     *       should not assert. That fails the ten-million-contract run on one bad row, i.e. it
     *       violates FR-905 outright.
     *   <li>File it under the closest category with the throwable attached. The contract is
     *       quarantined, produces no figure, and the record says exactly what happened.
     * </ul>
     *
     * <p>The second is chosen, and it is <em>not</em> the silent default FR-202 forbids. What
     * FR-202 forbids is defaulting a <b>treatment</b> — an unmapped fee assumed {@code INTEGRAL}
     * or {@code AS_INCURRED} — because either default silently changes a published figure in the
     * direction nobody checks. Here no figure is published at all: the category is a routing
     * label on a contract that has been removed from the population, and the detail and stack
     * trace carry the truth. A coarse label on a quarantined contract costs a queue filter; a
     * dead run costs the close.
     *
     * <p>Callers that know their stage should pass a better category to
     * {@link #isolate(String, String, ExceptionCategory, ContractWork)} — {@code NO_SOLUTION}
     * inside a solve, {@code MISSING_COST_FUNCTION} inside cost classification.
     */
    public static final ExceptionCategory UNRECOGNISED_FAILURE =
        ExceptionCategory.MISSING_MANDATORY_FIELD;

    private FailureIsolation() {
    }

    /**
     * One contract's unit of work, as a supplier that may throw.
     *
     * <p>Unchecked-only on purpose. A checked-throwing shape would let an
     * {@code InterruptedException} arrive at the barrier, where filing it as a contract exception
     * would both libel the contract and swallow a shutdown signal. Keeping the surface unchecked
     * makes {@code RuntimeException}-or-{@code Error} an exhaustive division of what can escape,
     * which is what lets the barrier be exactly one {@code catch}.
     */
    @FunctionalInterface
    public interface ContractWork<T> {
        T perform();
    }

    /**
     * The result of one isolated unit of work: either a value or a queue entry, never both and
     * never neither.
     *
     * <p>A value type rather than a nullable return, for the same reason
     * {@link com.crisil.eir.calc.solver.SolveStatus} is a value type: the caller has to look at
     * the failure to compile, and a failure that has to be looked at is a failure that reaches
     * the queue.
     *
     * @param value     the computed result, or null where the work failed
     * @param exception the queue entry, or null where the work succeeded
     */
    public record Outcome<T>(T value, ExceptionRecord exception) {

        public Outcome {
            if ((value == null) == (exception == null)) {
                throw new IllegalArgumentException(
                    "an isolated outcome is exactly one of a value and an exception; "
                        + (value == null ? "both were absent" : "both were present"));
            }
        }

        /** A successful outcome. */
        public static <T> Outcome<T> of(T value) {
            return new Outcome<>(Objects.requireNonNull(value, "value"), null);
        }

        /** A captured failure. */
        public static <T> Outcome<T> failed(ExceptionRecord exception) {
            return new Outcome<>(null, Objects.requireNonNull(exception, "exception"));
        }

        /** Whether the contract produced a figure. */
        public boolean succeeded() {
            return exception == null;
        }

        /** Whether the contract was quarantined. */
        public boolean failed() {
            return exception != null;
        }

        /** The value, or empty where the work failed. */
        public Optional<T> result() {
            return Optional.ofNullable(value);
        }

        /** The queue entry, or empty where the work succeeded. */
        public Optional<ExceptionRecord> failure() {
            return Optional.ofNullable(exception);
        }

        /**
         * The value, or a throw that keeps the original diagnostic as its cause.
         *
         * <p>For a caller that wanted the record for reporting but is not prepared to continue
         * without the figure. The original throwable is chained rather than summarised, because
         * a barrier that converts a stack trace into a sentence has lost the stack trace.
         */
        public T orElseThrow() {
            if (exception == null) {
                return value;
            }
            throw new IllegalStateException(
                "contract " + exception.contractId() + " was quarantined: "
                    + exception.describe(), exception.cause());
        }
    }

    /**
     * Runs one contract's work behind the barrier, filing an unrecognised failure under
     * {@link #UNRECOGNISED_FAILURE}.
     */
    public static <T> Outcome<T> isolate(String contractId, String runId, ContractWork<T> work) {
        return isolate(contractId, runId, UNRECOGNISED_FAILURE, work);
    }

    /**
     * Runs one contract's work behind the barrier.
     *
     * <p>The one {@code catch} is on {@link RuntimeException}. {@link Error} and any run-level
     * invariant breach propagate — see the class javadoc for why each of those must.
     *
     * <p><b>Everything the barrier needs is validated before the work runs.</b> The contract id
     * and the fallback category are checked up front, not at the point the record is built,
     * because a check inside the {@code catch} only fires on rows that also happen to throw:
     * a blank contract id would then fail a ten-million-contract run non-deterministically,
     * depending on whether that particular row was also malformed. A defect in the caller should
     * fail the same way every time.
     *
     * @param fallbackCategory what to file a throwable {@link #recognise} cannot name; a caller
     *                         that knows which stage it is guarding should name that stage's
     *                         category rather than accept the default. Must be a category that
     *                         stops the contract — see {@link #capturedCategory}
     * @throws Error                    always propagated: not a per-contract failure
     * @throws InvariantBreachException where the invariant is in {@link #RUN_LEVEL_INVARIANTS}
     */
    public static <T> Outcome<T> isolate(
        String contractId, String runId, ExceptionCategory fallbackCategory,
        ContractWork<T> work) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(fallbackCategory, "fallbackCategory");
        Objects.requireNonNull(work, "work");
        if (contractId.isBlank()) {
            throw new IllegalArgumentException(
                "the barrier was given a blank contract id in run " + runId + "; FR-905 isolates"
                    + " per contract, and a row nobody can name cannot be quarantined,"
                    + " reported on, or excluded from the population");
        }
        if (runId.isBlank()) {
            // Checked here for the same reason as the contract id, and it completes the argument:
            // with the id, the run, the category and the detail all guaranteed good before the
            // work starts, nothing inside the catch below can throw. An exception escaping the
            // catch would abort the batch and discard every figure computed so far — the barrier
            // killing the run it exists to keep alive.
            throw new IllegalArgumentException(
                "the barrier was given a blank run id for contract " + contractId + "; 04 § 2.13"
                    + " requires every exception to tie back to the run that raised it");
        }
        if (!fallbackCategory.stopsTheContract()) {
            throw new IllegalArgumentException(
                "fallback category " + fallbackCategory + " does not stop the contract, and the"
                    + " barrier only runs when the computation produced no figure; see"
                    + " capturedCategory");
        }
        T value;
        try {
            value = work.perform();
        } catch (RuntimeException failure) {
            if (failsTheRun(failure)) {
                // 05 § 4.5: the whole set is untrustworthy, so there is nothing to isolate.
                // Rethrown rather than wrapped, so the run's own handler sees the real type.
                throw failure;
            }
            return Outcome.failed(ExceptionRecord.captured(
                contractId, runId, capturedCategory(failure, fallbackCategory),
                capturedDetail(failure, fallbackCategory), failure));
        }
        if (value == null) {
            // Not caught and filed: a computation returning null is a defect in the engine, not
            // in the contract. Filing it would put an entry in the queue that nobody can fix
            // upstream, and returning it would hand the writer a null to persist as a figure.
            throw new IllegalStateException(
                "contract computation for " + contractId + " in run " + runId + " returned null;"
                    + " the barrier isolates malformed contracts, not stages that decline to"
                    + " answer");
        }
        return Outcome.of(value);
    }

    /**
     * Runs a whole batch behind the barrier, filing every failure into {@code queue} and
     * returning the contracts that produced figures — the shape FR-905 is actually about.
     *
     * <p>The guarantee this method exists to provide: <b>the contracts after a failing contract
     * are computed</b>. One malformed row among ten million leaves 9,999,999 results and one
     * queue entry, not zero results and a stack trace.
     *
     * <p>Results are keyed by contract id and ordered by input order — a {@link LinkedHashMap} —
     * because FR-903 requires the same inputs to produce byte-identical output, and an
     * iteration order that varies between runs makes byte-identical output impossible to
     * achieve.
     *
     * <p>A {@link Collection} rather than an {@link Iterable}, because the duplicate-id check
     * runs as a pass of its own before any work starts and that needs the input to be traversable
     * twice.
     *
     * @param contractIdOf how to name a contract; its result is what the queue entry is filed
     *                     under, so it is evaluated <em>outside</em> the barrier: a contract
     *                     whose id cannot be determined cannot be isolated per contract
     * @throws IllegalArgumentException on a duplicate contract id, which would otherwise
     *                                 silently overwrite one contract's figures with another's.
     *                                 Thrown before anything is computed or filed, so the queue
     *                                 is left as it was
     */
    public static <C, R> Map<String, R> runBatch(
        String runId, Collection<C> contracts, Function<C, String> contractIdOf,
        Function<C, R> computation, ExceptionQueue queue) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(contracts, "contracts");
        Objects.requireNonNull(contractIdOf, "contractIdOf");
        Objects.requireNonNull(computation, "computation");
        Objects.requireNonNull(queue, "queue");
        // Duplicates are found in a pass of their own, before a single contract is computed and
        // before a single exception is filed. Detecting them mid-loop instead would abort with
        // the caller's queue already holding entries from a run whose results were then
        // discarded: 04 § 2.13's exceptions_raised would be written from that queue and a
        // half-finished run would read as a completed one.
        Set<String> seen = new HashSet<>(Math.max(16, contracts.size() * 2));
        for (C contract : contracts) {
            if (!seen.add(contractIdOf.apply(contract))) {
                throw new IllegalArgumentException(
                    "contract " + contractIdOf.apply(contract) + " appears twice in run " + runId
                        + "; a duplicate would overwrite one contract's figures with another's"
                        + " and the loss would be invisible in the output");
            }
        }
        Map<String, R> results = new LinkedHashMap<>();
        for (C contract : contracts) {
            String contractId = contractIdOf.apply(contract);
            Outcome<R> outcome =
                isolate(contractId, runId, () -> computation.apply(contract));
            if (outcome.succeeded()) {
                results.put(contractId, outcome.value());
            } else {
                queue.raise(outcome.exception());
            }
        }
        // Unmodifiable but still insertion-ordered. Map.copyOf would do neither half of that:
        // it discards the order, and FR-903's byte-identical replay needs the order kept.
        return Collections.unmodifiableMap(results);
    }

    /**
     * The category this throwable is filed under, or {@code fallback} where the closed
     * ten-category set of 04 § 3 does not name it.
     */
    public static ExceptionCategory categorise(Throwable failure, ExceptionCategory fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return recognise(failure).orElse(fallback);
    }

    /**
     * The category the engine can <em>justify</em> for this throwable, or empty.
     *
     * <p>Kept separate from {@link #categorise} so that the difference between a category the
     * engine asserts and a category it fell back to stays visible in the code. Only these shapes
     * are recognised:
     *
     * <ul>
     *   <li>{@link InvariantBreachException} on {@code IC-1}, {@code TG-1}, {@code PC-1} or
     *       {@code HB-1} — the four invariants 04 § 3 gives their own categories. Any other
     *       contract-level breach falls through to the caller's fallback with the invariant id
     *       and its statement in the detail, because the ten categories name no general
     *       invariant-breach bucket.
     *   <li>{@link OptionalityUnresolvedException} → {@code MISSING_MANDATORY_FIELD}. Its own
     *       javadoc names the commonest cause as absent judgement inputs — "a most-likely date
     *       nobody supplied, a distribution nobody built" — which is that category exactly.
     *   <li>{@link UnsupportedScheduleShapeException} → {@code MISSING_MANDATORY_FIELD}. The
     *       weakest of these mappings and worth saying so: the shape field is present, it is the
     *       projector that is missing, so this is a configuration gap rather than a data gap. The
     *       ten categories have no home for it. The exception's message names the shape, the rate
     *       type and the registered projectors, so the queue entry is actionable even though its
     *       label is approximate.
     *   <li>{@link OptionalitySppiFailureException} → {@code MISSING_MANDATORY_FIELD}. The
     *       missing mandatory input is the classification determination: an SPPI failure means
     *       the instrument is at fair value through profit or loss and has no EIR at all, and
     *       reaching this exception means the classification gate (03 § 11) was skipped before
     *       EIR work was commissioned. The fix is upstream, and the category says a required
     *       input was not there.
     *   <li>{@link NullPointerException} → {@code MISSING_MANDATORY_FIELD}. Literally the
     *       category's definition — "a field the computation cannot proceed without" — and the
     *       commonest shape a malformed source row takes by the time it reaches a projector.
     * </ul>
     */
    public static Optional<ExceptionCategory> recognise(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        // The whole chain, not just the throwable at the top. A projector that rethrows an
        // IC-1 breach wrapped in an IllegalStateException has not stopped it being an IC-1
        // breach, and a barrier that only looks at the outermost type files it under the coarse
        // fallback and loses the one category the data model names for it.
        for (Throwable at : causeChain(failure)) {
            Optional<ExceptionCategory> recognised = recogniseDirectly(at);
            if (recognised.isPresent()) {
                return recognised;
            }
        }
        return Optional.empty();
    }

    private static Optional<ExceptionCategory> recogniseDirectly(Throwable failure) {
        if (failure instanceof InvariantBreachException breach) {
            return categoryOfInvariant(breach.result().id());
        }
        if (failure instanceof OptionalityUnresolvedException
            || failure instanceof UnsupportedScheduleShapeException
            || failure instanceof OptionalitySppiFailureException
            || failure instanceof NullPointerException) {
            return Optional.of(ExceptionCategory.MISSING_MANDATORY_FIELD);
        }
        return Optional.empty();
    }

    /**
     * The category to file a <em>captured</em> throwable under: {@link #categorise}, except that
     * a category which does not stop the contract is never filed by the barrier.
     *
     * <p>The distinction the barrier has to make and the queue cannot. {@code TG-1} maps to
     * {@link ExceptionCategory#STALE_EQUIVALENCE_TEST}, which reports
     * {@code stopsTheContract() == false} — and rightly, because 03 § 10.2's response to a stale
     * equivalence test is to demote the population to Tier 2 and <em>measure it properly</em>.
     * That is a statement about a computation that <b>succeeded</b> by a more expensive route.
     *
     * <p>The barrier only ever runs when the computation <b>aborted</b>. A TG-1 breach that
     * arrived as a throw produced no figure at all, so filing it as non-stopping would leave the
     * contract absent from the results and absent from
     * {@link ExceptionQueue#quarantinedContracts()} — a reported population short by one
     * contract with every control still tying, which is the exact failure this package exists to
     * prevent. So the recognised category is demoted to the caller's fallback, and
     * {@link #capturedDetail} records what the recognised category was, because the name of the
     * defect is still the most useful thing in the entry.
     *
     * <p>A demotion belongs on the <em>value</em> path — {@link ExceptionRecord#raise} alongside
     * a successful Tier 2 recomputation — and not here.
     */
    public static ExceptionCategory capturedCategory(
        Throwable failure, ExceptionCategory fallback) {
        ExceptionCategory recognised = categorise(failure, fallback);
        return recognised.stopsTheContract() ? recognised : fallback;
    }

    /**
     * Whether this throwable condemns the run rather than the contract.
     *
     * <p>Asked before anything is filed. See {@link #RUN_LEVEL_INVARIANTS}.
     */
    public static boolean failsTheRun(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        // The whole chain. A layer that rethrows an SL-1 breach wrapped in its own exception has
        // not made the sub-ledger tie: if the barrier only looked at the outermost type it would
        // isolate the breach as one contract's queue entry, the run would complete, and a close
        // could be signed off over a general ledger that does not reconcile. That is exactly what
        // 05 § 4.5 forbids, and the wrapping is invisible in the output.
        for (Throwable at : causeChain(failure)) {
            if (at instanceof InvariantBreachException breach
                && RUN_LEVEL_INVARIANTS.contains(breach.result().id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The throwable and its causes, outermost first. Complete, and it terminates.
     *
     * <p><b>No depth limit</b>, and that is a correctness requirement rather than a preference.
     * {@link #failsTheRun} and {@link #recognise} both walk this list, so a cap would mean an
     * SL-1 breach wrapped one layer deeper than the cap is not seen at all: the run completes and
     * a general ledger that does not reconcile is filed as one contract's queue entry. Spring
     * Batch, JPA and AOP proxies stack wrappers freely, so "deeper than thirty-two" is not a
     * hypothetical, and the truncation would be invisible in the output. For the same reason
     * {@link #capturedDetail} names the true root cause: a middle wrapper reported as the root is
     * a queue entry that actively misdirects whoever works it.
     *
     * <p>Termination comes from the identity set, not from a bound. {@code Throwable.initCause}
     * permits a cycle, and an unguarded walk over one loops forever on the batch thread — one
     * contract hanging its partition with no exception and no diagnostic, which is worse than any
     * filing decision this class could get wrong. Each link is visited at most once and a
     * throwable has at most one cause, so the walk is bounded by the number of distinct
     * throwables reachable, which is finite.
     *
     * <p>Identity comparison, as {@code Throwable.printStackTrace} does it: two distinct
     * throwables can compare equal under a badly written {@code equals}, and dropping a real link
     * would lose the root cause that names the defect.
     */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable at = failure;
        while (at != null && visited.add(at)) {
            chain.add(at);
            at = at.getCause();
        }
        return chain;
    }

    private static Optional<ExceptionCategory> categoryOfInvariant(InvariantId id) {
        return switch (id) {
            case IC_1 -> Optional.of(ExceptionCategory.IC1_BREACH);
            case TG_1 -> Optional.of(ExceptionCategory.STALE_EQUIVALENCE_TEST);
            case PC_1 -> Optional.of(ExceptionCategory.PENAL_CHARGE_REJECTED);
            case HB_1 -> Optional.of(ExceptionCategory.DISCONTINUED_HEDGE_NO_SCHEDULE);
            default -> Optional.empty();
        };
    }

    /**
     * The {@code detail} column for a captured throwable: the type, the message, the root cause
     * where the failure was chained, and the recognised category where the barrier had to demote
     * it.
     *
     * <p>The type is included even when the message reads well on its own, because
     * {@code MISSING_MANDATORY_FIELD} covers five distinct exception classes and the class name
     * is what separates them in a queue report. The root cause is included because a
     * {@code NullPointerException} wrapped three frames up presents as the wrapper, and the
     * wrapper is never the thing that needs fixing.
     *
     * <p>Never blank. {@link ExceptionRecord} refuses a blank detail — correctly, since a
     * category with no detail is unworkable — and that refusal happens inside the barrier's own
     * {@code catch}, so a throwable whose description came out empty would kill the batch the
     * barrier exists to keep alive. An anonymous {@code RuntimeException} subclass with no
     * message is exactly that case: {@code getSimpleName()} is the empty string for an anonymous
     * class. So the type name falls back to the binary name, which never is.
     */
    private static String capturedDetail(Throwable failure, ExceptionCategory fallback) {
        List<Throwable> chain = causeChain(failure);
        StringBuilder detail = new StringBuilder(typeName(failure));
        appendMessage(detail, failure);
        Throwable root = chain.get(chain.size() - 1);
        if (root != failure) {
            detail.append(" [caused by ").append(typeName(root));
            appendMessage(detail, root);
            detail.append(']');
        }
        ExceptionCategory recognised = categorise(failure, fallback);
        if (!recognised.stopsTheContract()) {
            // capturedCategory demoted it. The recognised name is still the most useful thing in
            // the entry, so it is kept in the detail rather than lost with the category.
            detail.append(" [recognised as ").append(recognised)
                .append(", filed as ").append(fallback)
                .append(": the computation aborted, so no figure was produced and the contract is")
                .append(" quarantined rather than remeasured]");
        }
        return detail.toString();
    }

    private static void appendMessage(StringBuilder detail, Throwable failure) {
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            detail.append(": ").append(failure.getMessage());
        }
    }

    /** The simple name, or the binary name where there is no simple name (anonymous classes). */
    private static String typeName(Throwable failure) {
        String simple = failure.getClass().getSimpleName();
        return simple.isBlank() ? failure.getClass().getName() : simple;
    }
}
