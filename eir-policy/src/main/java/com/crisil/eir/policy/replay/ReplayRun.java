package com.crisil.eir.policy.replay;

import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One execution of the engine over a period, reduced to what DT-1 compares: the figures it
 * published and the policy versions it cited.
 *
 * <p>Field list from {@code amortisation_run} in {@code V2__ledger_and_transition.sql}:
 * {@code run_id}, {@code period_id}, {@code replay_of_run_id} (with {@code is_replay} derivable
 * from it, which is what {@code ck_amortisation_run_replay_consistent} asserts, so it is derived
 * here rather than stored twice), and the version stamps. The schema names three version columns —
 * {@code policy_version_id}, {@code rule_set_version_id}, {@code routing_table_version_id} — and
 * this type carries a map keyed on {@link PolicyKind} instead, for the reason {@code PolicyKind}
 * gives in its own javadoc: there are seven kinds and they move on different clocks. Three columns
 * is three of seven, and a run that consulted a behavioural curve has nowhere to say so. The map
 * is a superset of the columns, and a persistence layer projecting it back onto them loses only the
 * kinds the schema has not yet grown a column for.
 *
 * <p>{@code input_digest} and {@code output_digest} are not carried, and the omission is the point
 * of this package. A digest comparison answers "did anything differ" and nothing else: it cannot
 * name the figure, cannot say whether the value moved or only its scale, and cannot distinguish a
 * figure difference from a policy divergence. The schema is right to hold digests — they are how a
 * ten-million-contract run notices cheaply — and a control that only holds digests is a control
 * whose every finding requires a second investigation to interpret.
 *
 * <h2>What this type deliberately does not check</h2>
 *
 * <p>Nothing here compares a published run against a replayed one. No guard requires the two to
 * carry the same keys, the same scales or the same policy versions. That is
 * {@link ReplayComparison}'s job and it must stay so: a constructor that refused a run whose
 * figures disagreed with another run's would make DT-1 unfailable, which this codebase treats as
 * worse than an absent control because it reads as coverage. The same reasoning
 * {@code JournalEntry} applies in allowing an unbalanced entry to be constructed so SL-2 has
 * something to detect.
 *
 * @param runId            {@code amortisation_run.run_id}
 * @param periodId         {@code YYYYMM}; must match the {@link ClosedPeriod} being replayed
 * @param replayOfRunId    the run this one replays, or null for an original run
 * @param figures          published figure key to amount, <b>at the scale published</b>
 * @param policyVersionIds the version id cited per kind — the "reading in force" the schema
 *                         comment says replay is impossible without
 */
public record ReplayRun(
    String runId,
    int periodId,
    String replayOfRunId,
    Map<String, Money> figures,
    Map<PolicyKind, String> policyVersionIds) {

    public ReplayRun {
        Objects.requireNonNull(figures, "figures");
        Objects.requireNonNull(policyVersionIds, "policyVersionIds");
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException(
                "a run needs an id; a replay names the run it reproduces"
                    + " (fk_amortisation_run_replay_of)");
        }
        runId = runId.strip();
        replayOfRunId = replayOfRunId == null || replayOfRunId.isBlank()
            ? null : replayOfRunId.strip();
        if (runId.equals(replayOfRunId)) {
            // A run that replays itself. The foreign key is self-referencing so the database
            // permits it, and it is nonsense: is_replay would be true of the original, and a
            // comparison against it is the tautology this package must not contain.
            throw new IllegalArgumentException(
                "run " + runId + " names itself as the run it replays; a run is not a replay of"
                    + " itself, and comparing one against itself is a control that cannot fail");
        }
        // Copied so that a store mutating its own map after a run resolved against it cannot
        // change what the comparison compared. PolicyVersionRegistry copies at construction for
        // the same stated reason: a registry that changed after a run resolved against it would
        // make the run's own audit trail unreproducible.
        figures = Map.copyOf(figures);
        Map<PolicyKind, String> stamps = new EnumMap<>(PolicyKind.class);
        for (Map.Entry<PolicyKind, String> entry : policyVersionIds.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "policy kind");
            String id = entry.getValue();
            if (id == null || id.isBlank()) {
                // An absent stamp and a blank one are the same fact and must read the same way,
                // or "which kinds did this run consult" has two answers. Absent means not
                // consulted; a blank string is a stamp somebody failed to write.
                throw new IllegalArgumentException(
                    "run " + runId + " stamps " + entry.getKey() + " with a blank version id;"
                        + " omit the kind if the run did not consult it — a blank stamp is a"
                        + " version nobody can resolve, not a kind nobody read");
            }
            stamps.put(entry.getKey(), id.strip());
        }
        policyVersionIds = Map.copyOf(stamps);
    }

    /** An original run — what a close published. */
    public static ReplayRun published(
        String runId,
        int periodId,
        Map<String, Money> figures,
        Map<PolicyKind, String> policyVersionIds) {
        return new ReplayRun(runId, periodId, null, figures, policyVersionIds);
    }

    /** A replay of {@code originalRunId} — what the nightly control produced. */
    public static ReplayRun replayOf(
        String runId,
        String originalRunId,
        int periodId,
        Map<String, Money> figures,
        Map<PolicyKind, String> policyVersionIds) {
        Objects.requireNonNull(originalRunId, "originalRunId");
        return new ReplayRun(runId, periodId, originalRunId, figures, policyVersionIds);
    }

    /** {@code amortisation_run.is_replay}, derived rather than stored — see the class comment. */
    public boolean isReplay() {
        return replayOfRunId != null;
    }

    public Optional<String> replayOf() {
        return Optional.ofNullable(replayOfRunId);
    }

    /** The figure under {@code key}, if this run produced one. */
    public Optional<Money> figure(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(figures.get(key));
    }

    /** The version id this run cited for {@code kind}, if it consulted that kind. */
    public Optional<String> versionOf(PolicyKind kind) {
        Objects.requireNonNull(kind, "kind");
        return Optional.ofNullable(policyVersionIds.get(kind));
    }

    /**
     * Every figure key, in a stable order.
     *
     * <p>Sorted rather than insertion-ordered, and the reason is DT-1 itself: the comparison's
     * own report has to read the same on every night it runs, or two replays of one period produce
     * two differently-ordered findings for the same defect and a reader comparing last night's
     * workpaper against tonight's sees changes that are not there. {@code Map.copyOf} gives no
     * iteration-order guarantee at all, which is exactly the hash-order leak
     * {@code PolicyVersionRegistry} makes its comparator total to avoid.
     */
    public Set<String> figureKeys() {
        return new TreeSet<>(figures.keySet());
    }

    /** The kinds this run consulted, in {@link PolicyKind} declaration order. */
    public Set<PolicyKind> consultedKinds() {
        Map<PolicyKind, String> ordered = new EnumMap<>(PolicyKind.class);
        ordered.putAll(policyVersionIds);
        return ordered.keySet();
    }

    public int figureCount() {
        return figures.size();
    }

    /** A one-line audit sentence naming the run, its period and its reading. */
    public String describe() {
        Map<String, String> stamps = new LinkedHashMap<>();
        for (PolicyKind kind : consultedKinds()) {
            stamps.put(kind.name(), policyVersionIds.get(kind));
        }
        return (isReplay() ? "replay " + runId + " of " + replayOfRunId : "run " + runId)
            + " over period " + periodId + ", " + figures.size() + " figures, policy "
            + (stamps.isEmpty() ? "(none cited)" : stamps.toString());
    }

    /** Convenience for the fixtures: the figure map from a collection of named figures. */
    public static Map<String, Money> figureMap(Collection<ReplayFigure> figures) {
        Objects.requireNonNull(figures, "figures");
        Map<String, Money> map = new LinkedHashMap<>();
        for (ReplayFigure figure : figures) {
            Money clash = map.putIfAbsent(figure.key(), figure.amount());
            if (clash != null) {
                throw new IllegalArgumentException(
                    "figure key '" + figure.key() + "' appears twice, as " + clash + " and "
                        + figure.amount() + "; a key that names two figures makes the comparison"
                        + " depend on which was found first");
            }
        }
        return map;
    }
}
