package com.crisil.eir.api.modules.approximations;

import com.crisil.eir.policy.pool.PoolDefinition;
import com.crisil.eir.policy.pool.SuspensionPools;
import com.crisil.eir.policy.tier.EquivalenceTestGate;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where {@link ApproximationRegister} reads each of FR-809's four categories from.
 *
 * <p><b>Every method returns an {@link Optional} of a list, and the two levels of emptiness are
 * the entire reason this interface exists.</b>
 *
 * <ul>
 *   <li>{@code Optional.empty()} — <b>no source.</b> The engine holds nothing this register can
 *       read. It becomes {@link CategoryReturn.Status#NOT_AVAILABLE}, a named gap, and it makes
 *       the whole report incomplete. It asserts <em>nothing</em> about whether the shortcut is in
 *       force.
 *   <li>{@code Optional.of(List.of())} — <b>a source that answered nothing.</b> Somebody looked
 *       and there is genuinely no such shortcut in the period. It becomes
 *       {@link CategoryReturn.Status#NONE_IN_FORCE}, which is a positive claim, and the report
 *       stays complete.
 * </ul>
 *
 * <p>A plain {@code List} return type could not express the difference, and the difference is the
 * whole of 06 § 7's argument for this endpoint: a category rendered as an empty list reads as
 * coverage, and "an undocumented approximation drifting quietly across a portfolio is the failure
 * this endpoint exists to prevent". An interface that made the two indistinguishable would put
 * that failure one careless implementation away, permanently. This one makes it a type error.
 *
 * <p>Each method also returns the <em>reason</em> alongside, through {@link Unavailable}, so a gap
 * can say what is missing. A gap that cannot is an apology nobody can act on.
 */
public interface ApproximationSources {

    /**
     * A category the engine cannot answer, and why.
     *
     * <p>The reason is one or two sentences naming the artefact, the class, and where the
     * requirement comes from — a reader of the endpoint should be able to open the named file. It
     * is stripped and must not be blank: {@link CategoryReturn#notAvailable} refuses a reasonless
     * gap, and this is the earlier of the two guards.
     *
     * @param reason why there is no source
     */
    record Unavailable(String reason) {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            reason = reason.strip();
            if (reason.isBlank()) {
                throw new IllegalArgumentException(
                    "an unavailable category must say what is missing; a gap with no reason"
                        + " renders as an empty list, and an empty list reads as \"none in"
                        + " force\"");
            }
        }
    }

    /**
     * Either what a source holds for a category, or the reason there is no source.
     *
     * <p>A two-field record with exactly one field set, rather than a sealed hierarchy, because
     * the register wants both shapes in one loop and the constructor can enforce the exclusivity
     * in four lines. The constructor's refusal of "both" and of "neither" is what stops an
     * implementation from returning a nothing that could be read either way.
     *
     * @param value       what the source holds, or null where there is no source
     * @param unavailable the reason there is no source, or null where there is one
     */
    record Answer<T>(T value, Unavailable unavailable) {
        public Answer {
            if ((value == null) == (unavailable == null)) {
                throw new IllegalArgumentException(
                    "an answer is either what the source holds or the reason there is no source,"
                        + " never both and never neither; "
                        + (value == null ? "neither was given" : "both were"));
            }
        }

        /** The source answered. An empty list inside is a claim that nothing is in force. */
        public static <T> Answer<T> of(T value) {
            return new Answer<>(Objects.requireNonNull(value, "value"), null);
        }

        /** There is no source. This is a gap, and the reason is mandatory. */
        public static <T> Answer<T> unavailable(String reason) {
            return new Answer<>(null, new Unavailable(reason));
        }

        public boolean isAvailable() {
            return value != null;
        }

        /** What the source holds, or an empty optional where this is a gap. */
        public Optional<T> present() {
            return Optional.ofNullable(value);
        }
    }

    /**
     * The Tier 3 populations proposed for the period, and the equivalence tests they are gated
     * against.
     *
     * <p>Both halves in one submission because neither answers FR-809 alone. Populations with no
     * gate is a list of shortcuts with no evidence column; a gate with no populations is an
     * evidence register with nothing to evidence. And critically, an <em>empty</em>
     * {@link EquivalenceTestGate} alongside a non-empty population list is not a gap — it is the
     * finding: {@code EquivalenceTestGate.evaluate} returns {@code NO_TEST_ON_FILE}, demotes the
     * population to Tier 2 and breaches TG-1, which is a row this register must publish rather
     * than a category it must decline to report.
     *
     * @param populations what FR-107 assignment proposed, in the order assigned
     * @param tests       the equivalence tests on file; {@link EquivalenceTestGate#empty()} where
     *                    there are none
     */
    record Tier3Submission(List<EquivalenceTestSubject> populations, EquivalenceTestGate tests) {
        public Tier3Submission {
            Objects.requireNonNull(populations, "populations");
            Objects.requireNonNull(tests, "tests");
            populations = List.copyOf(populations);
        }
    }

    /**
     * The pools measured collectively at the reporting date, and the back-tests over them.
     *
     * <p>Same pairing, same reason. A {@link PoolDefinition} in force is the shortcut; the
     * {@link PoolBackTest} is 03 § 10.1's evidence for it. A pool with no back-test is a row with
     * {@code evidenced: false}, not a missing category.
     *
     * @param pools     the definitions, resolved latest-wins per pool id by {@link SuspensionPools}
     * @param backTests every back-test on file, in any order; matched to pools by pool id
     */
    record PoolSubmission(SuspensionPools pools, List<PoolBackTest> backTests) {
        public PoolSubmission {
            Objects.requireNonNull(pools, "pools");
            Objects.requireNonNull(backTests, "backTests");
            backTests = List.copyOf(backTests);
        }
    }

    /** The Tier 3 populations and their equivalence tests, or the reason there are none on file. */
    Answer<Tier3Submission> tier3(int periodId);

    /** The contracts on FR-310's contractual fallback, or the reason the register cannot say. */
    Answer<List<ContractualLifeFallback>> contractualLifeFallbacks(int periodId);

    /** The pools measured collectively and their back-tests, or the reason. */
    Answer<PoolSubmission> pools(int periodId);

    /** The per-product ACPIR 54 elections in force, or the reason. */
    Answer<List<RevolvingElection>> revolvingElections(int periodId);
}
