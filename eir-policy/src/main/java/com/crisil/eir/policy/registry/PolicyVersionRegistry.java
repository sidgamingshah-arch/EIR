package com.crisil.eir.policy.registry;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The many versions of many {@link PolicyKind}s, and the one that governs a given date
 * (04 § 2.12, FR-210).
 *
 * <p>This is the type that makes the Phase 2 exit gate — "the routing table can be changed
 * without a code deploy" (08 roadmap, Phase 2) — true in the <em>time</em> dimension. Changing a
 * table without a deploy is only half the promise; the other half is that the change lands on a
 * date, that the previous reading keeps governing the periods it governed, and that no run ever
 * has to guess which of two readings applied.
 *
 * <h2>The rule, stated once</h2>
 *
 * <p><b>Resolution is latest-wins over the operative versions.</b> Among the versions of the
 * requested {@link PolicyKind} whose status {@code isOperative()} and whose
 * {@code effectiveFrom} is not after the date asked about, the answer is the one with the
 * <em>greatest</em> {@code effectiveFrom}. Nothing else breaks a tie — not id order, not
 * insertion order, not approval date — because a set in which anything else would be needed is
 * refused at construction (below).
 *
 * <p><b>Why there is no end-date column, and why one is not needed.</b>
 * {@code PolicyVersion.isEffectiveOn} is open-ended by design: it is
 * {@code status.isOperative() && !date.isBefore(effectiveFrom)}, and
 * {@code SUPERSEDED.isOperative()} is deliberately true so that a closed period still resolves
 * (invariant DT-1). So a superseded version answers true for every date after its own start,
 * forever, and <em>every</em> pair of same-kind versions therefore "overlaps" when read
 * one version at a time. That is not a defect to be patched with an {@code effectiveTo} column:
 * under latest-wins the successor's own start date <em>is</em> the predecessor's end date, which
 * is the standard temporal-table rule, and it needs no second column to maintain, no invariant
 * keeping the two columns consistent, and no migration of the version record that eir-calc's
 * {@code RoutingTableVersion} already shares. The end date is a property of the timeline, not of
 * the row.
 *
 * <p>The consequence is worth saying plainly, because reading a single version misleads: two
 * operative versions of one kind will both report themselves in force on a late date. Only the
 * registry can answer, and it answers with exactly one.
 *
 * <h2>What it refuses to do</h2>
 *
 * <p><b>A registry that silently picks a version is worse than no registry.</b> Under
 * latest-wins there is exactly one ambiguous shape, and this is it: two versions of the same kind
 * sharing an <em>identical</em> effective date. Then no rule over dates separates them, and which
 * governs is not a preference to be resolved by iteration order, insertion order or id ordering —
 * it is an unanswerable question about which policy governed a published figure, and "whichever
 * we found first" is not an accounting answer. So the clash is refused at construction — by
 * {@link #of(Collection)} throwing, before any date is ever resolved against it — rather than
 * discovered at a period close by a control that fires on the difference between two runs.
 *
 * <p>The refusal covers every version a checker has signed
 * ({@code PolicyVersionStatus.isApproved()}: {@code APPROVED}, {@code EFFECTIVE},
 * {@code SUPERSEDED}), not merely the operative ones. An {@code APPROVED} version sharing an
 * effective date with an {@code EFFECTIVE} one is not a latent problem — it is the same problem,
 * dated: on the day it goes effective both are operative and the date resolves to two answers.
 * Refusing it while the checker can still change the date is the difference between a
 * conversation and a restatement. {@code DRAFT} and {@code PENDING_APPROVAL} versions are free
 * to share a date, because two makers drafting competing candidates for 1 April is the normal
 * shape of a policy change in progress and neither is resolvable by anything.
 *
 * <p>Nothing else is refused. Two shapes that <em>are</em> resolvable under latest-wins but read
 * as defects — a replaced version left at {@code EFFECTIVE}, and a newest-operative version that
 * is {@code SUPERSEDED}, meaning the version that replaced it was never loaded — are reported by
 * {@link #supersessionCoherentFor(PolicyKind)} as an invariant result rather than refused,
 * because both are data conditions a run must collect and carry on from (FR-905) and neither
 * makes a date unanswerable.
 *
 * <h2>What it will not invent</h2>
 *
 * <p>A date before any version of a kind takes effect resolves to {@link Optional#empty()}, and
 * emphatically <em>not</em> to the earliest version. Returning the earliest would answer "what
 * governed 31 March 2027?" with a version effective 1 April 2027 — a rule applied to a period
 * that closed before it was written. FR-202's stance on an unmapped fee code is the same stance
 * for the same reason: the classification is the answer, not a fallback, and both available
 * defaults are wrong in the direction nobody checks.
 *
 * <h2>Why history stays resolvable</h2>
 *
 * <p>{@code SUPERSEDED} is operative. A replay of a closed period must reproduce the published
 * figures bit-identically (invariant DT-1, 03 § 9, control C-12), which is possible only if the
 * version that was in force when the period closed still resolves. Supersession is therefore an
 * end-date, not a deletion: {@link #inForceOn(PolicyKind, LocalDate)} for a date inside a closed
 * period keeps returning the superseded version for as long as the registry holds it.
 *
 * <p>{@link #findById(String)} is the other half of DT-1 and the one a replay should actually
 * use. Selection by date is for <em>new</em> work; a replay resolves the version id stored on
 * the event it is replaying (see {@code RoutingDecision.routingTableVersionId()} in
 * {@code eir-calc.routing}), which is why that id is stored at all. This registry keeps
 * unapproved versions addressable by id too, so a mis-stored id fails as "that version is not
 * operative" rather than as "no such version".
 *
 * <h2>The one thing date selection cannot answer</h2>
 *
 * <p>A backdated restatement taking effect from the same date as the version it replaces is
 * unrepresentable here, deliberately. Both would be operative on that date, and no rule over
 * dates distinguishes them — that is genuinely the "whichever we found first" case. Such a
 * restatement must either carry its own effective date, or be resolved through
 * {@link #findById(String)} against the id the closed period stored. The alternative — admitting
 * the pair and tie-breaking on approval date — would mean a replay of a closed period silently
 * switching readings, which is the exact failure DT-1 exists to catch.
 *
 * <p>Immutable, and defensively copied at construction: a registry that changed after a run
 * resolved against it would make the run's own audit trail unreproducible.
 */
public final class PolicyVersionRegistry {

    /**
     * Operative versions per kind, each list ordered by ascending effective date.
     *
     * <p>Kept per kind rather than as one list because the kinds are independent by design — a
     * fee repricing must not re-approve the routing table (ADR-0006, {@link PolicyKind}) — and a
     * fee version effective 1 April must not shadow, delay or answer for a routing version.
     * Partitioning by kind at construction makes that interference structurally impossible
     * rather than a property of a correctly-written filter at each call site.
     */
    private final Map<PolicyKind, List<PolicyVersion>> operativeByKind;

    /** Every version held, operative or not, by id — the replay lookup. */
    private final Map<String, PolicyVersion> byId;

    /** Every version held, in the order supplied, for audit listing. */
    private final List<PolicyVersion> all;

    private PolicyVersionRegistry(
        Map<PolicyKind, List<PolicyVersion>> operativeByKind,
        Map<String, PolicyVersion> byId,
        List<PolicyVersion> all) {
        this.operativeByKind = operativeByKind;
        this.byId = byId;
        this.all = all;
    }

    /**
     * Builds a registry, refusing any set that cannot answer a date unambiguously.
     *
     * @param versions the versions to hold; copied, so later mutation of the argument cannot
     *     change what a run resolved against
     * @return an immutable registry
     * @throws IllegalArgumentException if two approved versions of one kind share an identical
     *     effective date, or if one id names two different versions. Nothing else is refused:
     *     every other set is answerable under latest-wins, and the shapes that are answerable but
     *     wrong are reported by {@link #supersessionCoherentFor(PolicyKind)}
     * @throws NullPointerException if the collection or any element is null
     */
    public static PolicyVersionRegistry of(Collection<PolicyVersion> versions) {
        Objects.requireNonNull(versions, "versions");

        // Insertion-ordered so that the audit listing and every error message read in the order
        // the caller supplied, which is the order a reviewer is looking at.
        Map<String, PolicyVersion> byId = new LinkedHashMap<>();
        for (PolicyVersion version : versions) {
            Objects.requireNonNull(version, "versions must not contain null");
            PolicyVersion existing = byId.putIfAbsent(version.id(), version);
            if (existing != null && !existing.equals(version)) {
                // Two different versions under one id. Every computation cites a version id
                // (04 § 2.12) and a replay resolves that id, so an id naming two things makes
                // the closed period's own record ambiguous: the id would resolve to whichever
                // entry the map happened to keep, which is the same non-answer as picking a
                // version by iteration order.
                throw new IllegalArgumentException(
                    "policy version id '" + version.id() + "' names two different versions: "
                        + existing.describe() + " and " + version.describe()
                        + ". A computation cites an id; an id that names two versions makes a"
                        + " closed period unreplayable (invariant DT-1)");
            }
            // An identical record supplied twice is a duplicated row, not a conflict, and is
            // folded away silently: it changes no answer this registry can give.
        }

        Map<PolicyKind, List<PolicyVersion>> operative = new EnumMap<>(PolicyKind.class);
        // Keyed on the pair that has to be unique. A signed-off version is committed to its
        // date, so the clash is detected across APPROVED, EFFECTIVE and SUPERSEDED together
        // rather than only among the versions that are operative today.
        Map<PolicyKind, Map<LocalDate, PolicyVersion>> claimedDates =
            new EnumMap<>(PolicyKind.class);
        for (PolicyVersion version : byId.values()) {
            if (version.status().isApproved()) {
                PolicyVersion clash = claimedDates
                    .computeIfAbsent(version.kind(), kind -> new LinkedHashMap<>())
                    .putIfAbsent(version.effectiveFrom(), version);
                if (clash != null) {
                    throw new IllegalArgumentException(
                        "two approved " + version.kind() + " versions both take effect on "
                            + version.effectiveFrom() + ": '" + clash.id() + "' and '"
                            + version.id() + "'. Which one governs that date is not resolvable by"
                            + " date, and picking whichever was found first is not an accounting"
                            + " answer — give one of them a different effective date, or resolve"
                            + " the restatement by version id");
                }
            }
            if (version.status().isOperative()) {
                operative.computeIfAbsent(version.kind(), kind -> new ArrayList<>()).add(version);
            }
        }

        // Ascending by effective date. The id is a secondary key only so that the comparator is
        // total and the ordering is byte-for-byte the same on every run — a precondition of
        // DT-1 — never a tie-break that decides which version governs: the clash check above
        // has already ruled out two operative versions sharing a date.
        Comparator<PolicyVersion> byEffectiveDate =
            Comparator.comparing(PolicyVersion::effectiveFrom).thenComparing(PolicyVersion::id);
        Map<PolicyKind, List<PolicyVersion>> frozen = new EnumMap<>(PolicyKind.class);
        for (Map.Entry<PolicyKind, List<PolicyVersion>> entry : operative.entrySet()) {
            List<PolicyVersion> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(byEffectiveDate);
            frozen.put(entry.getKey(), Collections.unmodifiableList(sorted));
        }

        return new PolicyVersionRegistry(
            Collections.unmodifiableMap(frozen),
            Collections.unmodifiableMap(byId),
            List.copyOf(byId.values()));
    }

    /**
     * Whether one kind's operative timeline says the same thing its statuses say.
     *
     * <p>Reported, never thrown, and never a construction refusal: under latest-wins both shapes
     * below still resolve to exactly one version, so refusing them would reject sets the rule
     * says are answerable. They are nonetheless worth naming, because each is a statement about
     * the book that somebody believes and that is not true.
     *
     * <p><b>A replaced version still marked {@code EFFECTIVE}.</b> Resolution is unaffected — the
     * later version wins on date — but the status record now claims two versions are in force,
     * and a reader who takes {@code PolicyVersion.isEffectiveOn} or
     * {@code status() == EFFECTIVE} at face value over {@link #historyOf} gets two governing
     * versions for one date. Supersession is what replacement means; the predecessor's status
     * should record it.
     *
     * <p><b>A newest-operative version that is {@code SUPERSEDED}.</b> Its own status says
     * something replaced it, and that something is not on file, so latest-wins answers every
     * later date with a rule set the bank has already retired. That is the shape a partial load
     * produces — a filtered policy table, a fiscal-year-scoped query, one failed row — and it is
     * the reason this check exists at all: {@link #policyResolvableOn} reports satisfied
     * throughout, because a version does resolve. It is simply the wrong one.
     *
     * <p>A history-only registry is therefore constructible and useful for exactly one thing:
     * resolving stored version ids through {@link #findById(String)}, which a replay does anyway.
     * Asking it for a date is what this check warns about.
     *
     * <p>Deviation is the number of versions implicated rather than an amount — the breach has no
     * money size, and a count is what a caller can act on. See
     * {@link #policyResolvableOn(PolicyKind, LocalDate)} for why {@link InvariantId#DT_1} carries
     * this and why results under it must not be conjoined with replay results.
     */
    public InvariantResult supersessionCoherentFor(PolicyKind kind) {
        Objects.requireNonNull(kind, "kind");
        List<PolicyVersion> ascending = historyOf(kind);
        if (ascending.isEmpty()) {
            // No timeline is not an incoherent timeline. Whether a kind ought to be on file is
            // policyResolvableOn's question, asked of a date; this one is about what the
            // statuses of the versions held say about each other.
            return InvariantResult.pass(InvariantId.DT_1, "no operative " + kind + " version held");
        }

        List<String> defects = new ArrayList<>();
        for (int i = 0; i < ascending.size() - 1; i++) {
            PolicyVersion earlier = ascending.get(i);
            PolicyVersion later = ascending.get(i + 1);
            if (earlier.status() != PolicyVersionStatus.SUPERSEDED) {
                defects.add("'" + earlier.id() + "' effective " + earlier.effectiveFrom()
                    + " is still " + earlier.status() + " although '" + later.id()
                    + "' takes effect on " + later.effectiveFrom()
                    + ", so both report themselves in force from " + later.effectiveFrom()
                    + " onwards — mark the replaced version SUPERSEDED, which is what"
                    + " replacement means and is what keeps it resolvable for its own closed"
                    + " periods");
            }
        }

        PolicyVersion newest = ascending.get(ascending.size() - 1);
        if (newest.status() == PolicyVersionStatus.SUPERSEDED) {
            defects.add("'" + newest.id() + "' effective " + newest.effectiveFrom()
                + " is the newest operative version and is SUPERSEDED, so whatever replaced it"
                + " is not on file" + describeMissingSuccessor(kind, newest, byId.values())
                + ". Every date from " + newest.effectiveFrom() + " onwards therefore resolves to"
                + " a version the bank has already retired. Load the successor, or mark this"
                + " version EFFECTIVE if it was never replaced");
        }

        if (defects.isEmpty()) {
            return InvariantResult.pass(
                InvariantId.DT_1,
                kind + " timeline coherent: " + describeHistory(kind) + ", newest "
                    + newest.status());
        }
        return InvariantResult.fail(
            InvariantId.DT_1,
            kind + " timeline contradicts its own statuses — " + String.join("; ", defects),
            BigDecimal.valueOf(defects.size()));
    }

    /**
     * Names a successor that exists but is not operative, where there is one.
     *
     * <p>The two causes read very differently to whoever has to fix it. A successor sitting at
     * {@code APPROVED} means the status advance was half-done — the predecessor was retired
     * before its replacement went live, leaving a gap in the middle of the timeline rather than
     * at the end of it. No successor at all means the row was never loaded. Guessing between
     * those from an error message that mentions neither is the slow part of the investigation.
     */
    private static String describeMissingSuccessor(
        PolicyKind kind, PolicyVersion superseded, Collection<PolicyVersion> allHeld) {
        // The earliest such successor, not the first encountered: the message must read the same
        // whatever order the rows were loaded in, or two loads of one table produce two
        // different diagnoses of one defect.
        PolicyVersion earliestSuccessor = null;
        for (PolicyVersion candidate : allHeld) {
            if (candidate.kind() != kind
                || candidate.status().isOperative()
                || !candidate.effectiveFrom().isAfter(superseded.effectiveFrom())) {
                continue;
            }
            if (earliestSuccessor == null
                || candidate.effectiveFrom().isBefore(earliestSuccessor.effectiveFrom())
                || (candidate.effectiveFrom().isEqual(earliestSuccessor.effectiveFrom())
                    && candidate.id().compareTo(earliestSuccessor.id()) < 0)) {
                earliestSuccessor = candidate;
            }
        }
        if (earliestSuccessor == null) {
            return " (no later version of this kind is held at all)";
        }
        return " ('" + earliestSuccessor.id() + "' effective " + earliestSuccessor.effectiveFrom()
            + " is " + earliestSuccessor.status() + ", so it governs nothing yet)";
    }

    /** Builds a registry from versions named inline; {@code of()} builds an empty one. */
    public static PolicyVersionRegistry of(PolicyVersion... versions) {
        Objects.requireNonNull(versions, "versions");
        return of(List.of(versions));
    }

    /**
     * The one version of {@code kind} that governs {@code date}, or empty if none does.
     *
     * <p>Latest-wins, as stated in the class comment: the answer is the operative version with
     * the greatest effective date not after {@code date}, because the successor's start date is
     * the predecessor's end date. Several operative versions will report themselves in force on a
     * late date — that is what an open-ended {@code isEffectiveOn} means — and this is the only
     * thing entitled to say which of them governs. Empty means no version of this kind had taken
     * effect by {@code date}; it does not mean "use the earliest".
     *
     * <p>Nothing but the effective date decides the answer. No id ordering, no insertion order,
     * no approval date: uniqueness is a property of construction rather than of this search,
     * because {@link #of(Collection)} has already refused any set in which two operative versions
     * claim the same date, which is the only case a further tie-break could arise in.
     */
    public Optional<PolicyVersion> inForceOn(PolicyKind kind, LocalDate date) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(date, "date");
        List<PolicyVersion> history = operativeByKind.get(kind);
        if (history == null) {
            return Optional.empty();
        }
        // Walk backwards from the latest: the list is ascending and each entry governs until the
        // next begins, so the first version whose effective date has arrived is the one in
        // force. isEffectiveOn is PolicyVersion's own predicate — the status test (operative)
        // and the date test (not before effectiveFrom) are asked of the version rather than
        // restated here, so there is one definition of "in force" in the codebase and not two.
        for (int i = history.size() - 1; i >= 0; i--) {
            PolicyVersion candidate = history.get(i);
            if (candidate.isEffectiveOn(date)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Every kind's version in force on {@code date}, as one snapshot.
     *
     * <p>What a run stamps on its own output: an amortisation run resolves its whole
     * interpretive hierarchy once, at the run date, and stores it, so that the run's figures and
     * the policy that produced them are one record (04 § 2.13, FR-903). Kinds with nothing in
     * force on {@code date} are <em>absent</em> rather than mapped to null — an absent key is a
     * question the registry declined to answer, and a caller that needs an answer must treat the
     * gap as a hard stop (FR-905) rather than iterate over nulls.
     */
    public Map<PolicyKind, PolicyVersion> inForceOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        Map<PolicyKind, PolicyVersion> snapshot = new EnumMap<>(PolicyKind.class);
        for (PolicyKind kind : operativeByKind.keySet()) {
            inForceOn(kind, date).ifPresent(version -> snapshot.put(kind, version));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * The version in force, for a caller that cannot proceed without one.
     *
     * <p>{@link #inForceOn(PolicyKind, LocalDate)} is the primary form and this is a convenience
     * over it, not a second policy: a production caller inside a ten-million-contract run should
     * raise an exception-queue entry for the contract and carry on (FR-905), not let a throw
     * abandon the run. Use this where an absent version is a configuration defect rather than
     * contract data — a run bootstrapping its policy snapshot, or a test.
     *
     * @throws IllegalStateException naming the kind, the date and what the registry does hold
     */
    public PolicyVersion requireInForceOn(PolicyKind kind, LocalDate date) {
        return inForceOn(kind, date).orElseThrow(() -> new IllegalStateException(
            "no operative " + kind + " version is in force on " + date + "; the registry holds "
                + describeHistory(kind)
                + ". A date before the earliest version resolves to nothing, not to the earliest"
                + " version — a rule cannot govern a period that closed before it was written"));
    }

    /**
     * The version with this id, whether or not it is operative — the replay lookup.
     *
     * <p>Unapproved versions stay addressable on purpose. A stored id that resolves to a
     * {@code DRAFT} is a defect worth naming precisely; the same id returning empty would read
     * as data loss and send the investigation to the wrong place.
     */
    public Optional<PolicyVersion> findById(String id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * The operative versions of one kind, oldest first — the audit trail of what governed when.
     *
     * <p>Includes {@code SUPERSEDED} versions, which is the point: this is the sequence a
     * reviewer reads to see that every date since the first version has exactly one governing
     * reading, and that none was deleted when it was replaced.
     */
    public List<PolicyVersion> historyOf(PolicyKind kind) {
        Objects.requireNonNull(kind, "kind");
        return operativeByKind.getOrDefault(kind, List.of());
    }

    /** The kinds this registry can resolve at all — those with at least one operative version. */
    public Set<PolicyKind> resolvableKinds() {
        return operativeByKind.keySet();
    }

    /** Every version held, operative or not, in the order supplied. */
    public List<PolicyVersion> versions() {
        return all;
    }

    /** How many versions are held, counting unapproved ones. */
    public int size() {
        return all.size();
    }

    /**
     * Invariant DT-1, as a result rather than a throw: the date asked about resolves to exactly
     * one version of {@code kind}.
     *
     * <p>Asserted in production and not only in tests, because the case it catches is invisible
     * otherwise. A replay of a closed period must reproduce the published figures
     * bit-identically (03 § 9, control C-12), and it cannot do that if the version that governed
     * the period has been dropped from the registry, or if the period predates every version on
     * file. Neither failure looks like an error at the call site — one produces a run under a
     * silently different policy, the other a run under none — so the check is positive: it
     * asserts resolvability rather than trusting it, in the same spirit as the penal-charge
     * assertion of invariant PC-1.
     *
     * <p>Returned rather than thrown, per the module convention: a missing policy version for
     * one contract's date is a data condition to be collected and reported across a run
     * (FR-905), not a reason to abandon it.
     *
     * <p>Reported with a deviation of {@link BigDecimal#ONE} rather than an amount: the breach is
     * the absence of an answer, which has no money size. A caller summing deviations therefore
     * gets a count of unresolvable dates, which is the actionable figure.
     *
     * <p>Uses {@link InvariantId#DT_1} because no identifier for policy resolvability exists
     * yet. The condition is a <em>precondition</em> of deterministic replay rather than replay
     * itself, so a dedicated id would read better in a control workpaper; that enum belongs to
     * another work unit, and the id needed is noted rather than added here.
     *
     * <p><b>Do not conjoin this result with a bit-identical-replay DT-1 result until that
     * dedicated id exists.</b> {@code InvariantResult.conjunction} keeps the <em>first</em>
     * breach's deviation among results sharing an id — a defect its own javadoc records having
     * been found three times in this engine — so a list carrying both would publish a DT-1
     * deviation that is a count of unresolvable dates or a money amount depending on list order,
     * and no reader could tell which. Report this result on its own, or gate it before the
     * replay comparison runs, which is where it belongs anyway: a period whose policy does not
     * resolve cannot be replayed at all.
     */
    public InvariantResult policyResolvableOn(PolicyKind kind, LocalDate date) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(date, "date");
        Optional<PolicyVersion> resolved = inForceOn(kind, date);
        if (resolved.isPresent()) {
            PolicyVersion governing = resolved.get();
            return InvariantResult.pass(
                InvariantId.DT_1,
                kind + " on " + date + " resolves to version " + governing.id() + " (effective "
                    + governing.effectiveFrom() + ", " + governing.status() + ")");
        }
        return InvariantResult.fail(
            InvariantId.DT_1,
            "no operative " + kind + " version resolves for " + date + "; the registry holds "
                + describeHistory(kind)
                + ". A period cannot be replayed against a policy that is not on file",
            BigDecimal.ONE);
    }

    /** A one-line audit sentence: what governed each kind on {@code date}. */
    public String describeInForceOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        Map<PolicyKind, PolicyVersion> snapshot = inForceOn(date);
        if (snapshot.isEmpty()) {
            return "no policy version in force on " + date;
        }
        StringBuilder sentence = new StringBuilder("in force on ").append(date).append(": ");
        boolean first = true;
        for (Map.Entry<PolicyKind, PolicyVersion> entry : snapshot.entrySet()) {
            if (!first) {
                sentence.append("; ");
            }
            first = false;
            sentence.append(entry.getValue().describe());
        }
        return sentence.toString();
    }

    /** What the registry holds for one kind, for an error message that can be acted on. */
    private String describeHistory(PolicyKind kind) {
        List<PolicyVersion> history = historyOf(kind);
        if (history.isEmpty()) {
            return "no operative " + kind + " version at all";
        }
        StringBuilder held = new StringBuilder();
        for (PolicyVersion version : history) {
            if (held.length() > 0) {
                held.append(", ");
            }
            held.append(version.id()).append(" from ").append(version.effectiveFrom());
        }
        return held.toString();
    }
}
