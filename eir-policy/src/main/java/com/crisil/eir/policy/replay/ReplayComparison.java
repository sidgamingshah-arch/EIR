package com.crisil.eir.policy.replay;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A published run against a replay of it, reported as invariant DT-1 (FR-903, control C-12,
 * 03 § 9).
 *
 * <h2>Both halves of FR-903</h2>
 *
 * <p>"Replay any prior period <b>bit-identically</b> <b>under the policy then in force</b>". This
 * class asserts both, and a breach of either is one DT-1 failure.
 *
 * <p><b>Bit-identically.</b> Figures are compared through {@link ReplayFigure#bitIdentical}, which
 * compares the rendered decimal and is therefore scale-sensitive. {@link Money#equals} is never
 * called anywhere in this package: it compares by numeric value and ignores scale — correctly, for
 * a type whose job is accounting arithmetic — and so it reports {@code 1.0} and {@code 1.00} as a
 * match. That pair is not a match for DT-1. It is two different published artefacts, and
 * {@link DiscrepancyKind#SCALE_ONLY} is the finding that says so. Its money size is exactly zero,
 * which is the reason this control's deviation is a count.
 *
 * <p><b>Under the policy then in force.</b> The comparison carries the policy version ids each run
 * cited and fails when they diverge, <em>even when every figure matches</em>. The reasoning is
 * short: reproducing the right number from the wrong rule is luck. It says nothing about the engine
 * being deterministic, it will not hold next period, and by the time it stops holding the
 * divergence will be attributed to whatever changed most recently. The concrete defect is a replay
 * harness resolving policy at {@code LocalDate.now()} rather than at the period end — which works
 * perfectly until the first supersession, and then silently reproduces a closed period under a rule
 * written after it closed.
 *
 * <p>The expectation is resolved through {@link PolicyVersionRegistry#inForceOn(PolicyKind,
 * java.time.LocalDate)} at {@link ClosedPeriod#policyResolutionDate()} — the period <b>end</b>, not
 * the close date; that method carries the off-by-one and why it matters. The registry is a required
 * argument rather than an optional one, because "under the policy then in force" is not an optional
 * half of the requirement, and a comparison constructed without a timeline could only compare the
 * two runs' stamps against each other and would pass a pair that both cite the wrong version.
 *
 * <h2>One result, deviation a count</h2>
 *
 * <p>Exactly one DT-1 result is published, per {@link InvariantResult#conjunction}'s rule: it keeps
 * only the <em>first</em> breach's deviation among results sharing an id, so a comparison
 * publishing one result per differing figure would report one difference and silently drop the
 * rest. See {@link #dtOne()} for what the count counts and why a policy divergence is inside it.
 *
 * <h2>What is thrown rather than reported</h2>
 *
 * <p>Data conditions are reported; the three refusals below are harness defects that make the
 * report meaningless rather than adverse, and nothing downstream can compensate for them:
 *
 * <ul>
 *   <li>the two runs cover different periods — a replay of March compared against April's
 *       publication produces a full set of findings none of which is a defect;</li>
 *   <li>the two runs are the same run — a comparison of a run against itself cannot fail, and this
 *       codebase treats a control that cannot fail as worse than an absent one;</li>
 *   <li>the reference side is itself a replay — DT-1's reference is the artefact the close
 *       published. Two replays agreeing with each other proves they agree with each other.</li>
 * </ul>
 */
public final class ReplayComparison {

    /** How many findings the DT-1 detail names in full before summarising the remainder. */
    private static final int FINDINGS_NAMED_IN_DETAIL = 8;

    private final ClosedPeriod period;
    private final ReplayRun published;
    private final ReplayRun replayed;
    private final Map<PolicyKind, String> policyInForceAtPeriodEnd;
    private final List<ReplayDiscrepancy> discrepancies;

    private ReplayComparison(
        ClosedPeriod period,
        ReplayRun published,
        ReplayRun replayed,
        Map<PolicyKind, String> policyInForceAtPeriodEnd,
        List<ReplayDiscrepancy> discrepancies) {
        this.period = period;
        this.published = published;
        this.replayed = replayed;
        this.policyInForceAtPeriodEnd = policyInForceAtPeriodEnd;
        this.discrepancies = discrepancies;
    }

    /**
     * Compares a replay against the run whose figures were published.
     *
     * @param period    the closed period both runs cover; supplies the policy resolution date
     * @param published the original run — the published artefact DT-1 measures against
     * @param replayed  the replay produced by control C-12
     * @param registry  the policy timeline, resolved at the period end
     * @throws IllegalArgumentException on the three harness defects listed in the class comment
     */
    public static ReplayComparison of(
        ClosedPeriod period,
        ReplayRun published,
        ReplayRun replayed,
        PolicyVersionRegistry registry) {

        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(replayed, "replayed");
        Objects.requireNonNull(registry, "registry");

        if (published.periodId() != period.periodId()
            || replayed.periodId() != period.periodId()) {
            throw new IllegalArgumentException(
                "period mismatch: comparing over " + period.describe() + " but the published run"
                    + " covers " + published.periodId() + " and the replay covers "
                    + replayed.periodId()
                    + ". Every figure would differ and none of the differences would be a defect");
        }
        if (published.runId().equals(replayed.runId())) {
            throw new IllegalArgumentException(
                "run " + published.runId() + " compared against itself. Every figure is"
                    + " bit-identical to itself and every version id equal to itself, so this"
                    + " comparison cannot fail — which reads as DT-1 coverage and is not");
        }
        if (published.isReplay()) {
            throw new IllegalArgumentException(
                "the reference side of DT-1 is the run the close published, and "
                    + published.runId() + " is itself a replay of " + published.replayOfRunId()
                    + ". Two replays agreeing with each other proves they agree with each other,"
                    + " not that either reproduces what was reported");
        }

        Map<PolicyKind, String> inForce = policyThenInForce(registry, period);
        List<ReplayDiscrepancy> findings = new ArrayList<>();
        findings.addAll(compareFigures(published, replayed));
        findings.addAll(comparePolicy(published, replayed, inForce));
        return new ReplayComparison(
            period, published, replayed, inForce, List.copyOf(findings));
    }

    /**
     * The version id in force for each kind at the period end — what a replay of this period ought
     * to resolve.
     *
     * <p>Exposed as a static so a replay harness can <em>use</em> it rather than reinvent the
     * resolution and then be measured against it. That is not a courtesy: the failure this half of
     * FR-903 catches is a harness resolving policy the easy way, and the easy way being wrong is
     * only a trap while the right way is more work than the wrong one.
     *
     * <p>Kinds with nothing operative on the date are <em>absent</em> rather than mapped to null,
     * following {@code PolicyVersionRegistry.inForceOn(LocalDate)}: an absent key is a question the
     * registry declined to answer, and it must not be confused with a kind that resolved.
     */
    public static Map<PolicyKind, String> policyThenInForce(
        PolicyVersionRegistry registry, ClosedPeriod period) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(period, "period");
        Map<PolicyKind, String> ids = new EnumMap<>(PolicyKind.class);
        for (Map.Entry<PolicyKind, PolicyVersion> entry
            : registry.inForceOn(period.policyResolutionDate()).entrySet()) {
            ids.put(entry.getKey(), entry.getValue().id());
        }
        return Map.copyOf(ids);
    }

    /**
     * The figure leg: every key either run produced, compared for bit-identity.
     *
     * <p>The union rather than the published set. Iterating the published figures and looking each
     * one up would miss {@link DiscrepancyKind#ABSENT_FROM_PUBLICATION} entirely — a replay running
     * a wider population than the close did, which is what today's eligibility rule applied to
     * last year's period looks like.
     *
     * <p>Keys are walked in sorted order so the finding list is byte-identical on every night the
     * control runs. A report whose ordering drifts between two replays of one period shows a reader
     * differences that are not there, which is an odd way for a determinism control to behave.
     */
    private static List<ReplayDiscrepancy> compareFigures(ReplayRun published, ReplayRun replayed) {
        Set<String> keys = new TreeSet<>(published.figureKeys());
        keys.addAll(replayed.figureKeys());

        List<ReplayDiscrepancy> findings = new ArrayList<>();
        for (String key : keys) {
            Optional<Money> before = published.figure(key);
            Optional<Money> after = replayed.figure(key);
            if (before.isPresent() && after.isEmpty()) {
                findings.add(new ReplayDiscrepancy(
                    DiscrepancyKind.MISSING_FROM_REPLAY, key,
                    ReplayFigure.render(before.get()), ReplayDiscrepancy.ABSENT));
                continue;
            }
            if (before.isEmpty() && after.isPresent()) {
                findings.add(new ReplayDiscrepancy(
                    DiscrepancyKind.ABSENT_FROM_PUBLICATION, key,
                    ReplayDiscrepancy.ABSENT, ReplayFigure.render(after.get())));
                continue;
            }
            Money publishedAmount = before.orElseThrow();
            Money replayedAmount = after.orElseThrow();
            // IDENTITY question first: did the replay publish the same figure? Scale-sensitive,
            // and the only question DT-1 actually asks. Everything below is classification of a
            // difference this has already found.
            if (ReplayFigure.bitIdentical(publishedAmount, replayedAmount)) {
                continue;
            }
            DiscrepancyKind kind;
            if (!publishedAmount.currency().getCurrencyCode()
                .equals(replayedAmount.currency().getCurrencyCode())) {
                kind = DiscrepancyKind.CURRENCY_DIFFERS;
            } else if (ReplayFigure.numericallyEqual(publishedAmount, replayedAmount)) {
                // VALUE question, asked with compareTo, and asked only to distinguish two
                // diagnoses: the value survived and the presentation did not (find the rounding
                // point that moved) from the value itself moving (find the arithmetic that
                // changed). This is the branch Money.equals would have swallowed whole.
                kind = DiscrepancyKind.SCALE_ONLY;
            } else {
                kind = DiscrepancyKind.VALUE_DIFFERS;
            }
            findings.add(new ReplayDiscrepancy(
                kind, key,
                ReplayFigure.render(publishedAmount), ReplayFigure.render(replayedAmount)));
        }
        return findings;
    }

    /**
     * The policy leg: at most one finding per kind, so the count stays a count of kinds.
     *
     * <p>Two expectations are checked and they are not the same claim. Against the published run's
     * stamp: did the replay resolve what the close resolved. Against the timeline: was that version
     * the one in force for the period. The first catches a replay reading today's rule; the second
     * catches a period whose own record cites a rule that never governed it, which the first cannot
     * see because both runs agree.
     *
     * <p>The timeline check is reached only where the two stamps already agree. A kind that has
     * already produced a finding does not produce a second: the deviation is a count of kinds
     * needing a remedy, and one kind resolved wrongly is one remedy however many ways it is wrong.
     */
    private static List<ReplayDiscrepancy> comparePolicy(
        ReplayRun published, ReplayRun replayed, Map<PolicyKind, String> inForceAtPeriodEnd) {

        // Declaration order, via a LinkedHashSet over PolicyKind.values(), so the report reads the
        // same every night regardless of which kinds each run happened to consult.
        Set<PolicyKind> consulted = new LinkedHashSet<>();
        for (PolicyKind kind : PolicyKind.values()) {
            if (published.versionOf(kind).isPresent() || replayed.versionOf(kind).isPresent()) {
                consulted.add(kind);
            }
        }

        List<ReplayDiscrepancy> findings = new ArrayList<>();
        for (PolicyKind kind : consulted) {
            Optional<String> citedAtClose = published.versionOf(kind);
            Optional<String> citedOnReplay = replayed.versionOf(kind);

            if (citedAtClose.isPresent() && citedOnReplay.isEmpty()) {
                findings.add(new ReplayDiscrepancy(
                    DiscrepancyKind.POLICY_VERSION_MISSING_FROM_REPLAY, kind.name(),
                    citedAtClose.get(), ReplayDiscrepancy.ABSENT));
                continue;
            }
            if (citedAtClose.isEmpty()) {
                findings.add(new ReplayDiscrepancy(
                    DiscrepancyKind.POLICY_VERSION_ABSENT_FROM_PUBLICATION, kind.name(),
                    ReplayDiscrepancy.ABSENT, citedOnReplay.orElseThrow()));
                continue;
            }
            if (!citedAtClose.get().equals(citedOnReplay.orElseThrow())) {
                findings.add(new ReplayDiscrepancy(
                    DiscrepancyKind.POLICY_VERSION_DIFFERS, kind.name(),
                    citedAtClose.get(), citedOnReplay.get()));
                continue;
            }
            String agreed = citedAtClose.get();
            String governing = inForceAtPeriodEnd.get(kind);
            if (!agreed.equals(governing)) {
                findings.add(new ReplayDiscrepancy(
                    DiscrepancyKind.POLICY_NOT_IN_FORCE_AT_PERIOD_END, kind.name(),
                    governing == null ? ReplayDiscrepancy.ABSENT : governing, agreed));
            }
        }
        return findings;
    }

    /**
     * Invariant DT-1: one result, deviation the count of things that did not reproduce.
     *
     * <p><b>What input makes this fail?</b> Several, and each is reachable from data this package
     * accepts without complaint:
     *
     * <ul>
     *   <li>a replayed figure whose rendered decimal differs from the published one — including
     *       {@code 1.0} against {@code 1.00}, where the amounts are numerically identical and
     *       {@code Money.equals} would report a match;</li>
     *   <li>a figure the close published that the replay did not produce, or the reverse;</li>
     *   <li>a policy version id the replay resolved that differs from the one the close cited —
     *       <b>with every figure bit-identical</b>, which is the most valuable failure this control
     *       has, because nothing else in the engine can see it;</li>
     *   <li>both runs citing a version that the registry does not resolve for the period end,
     *       which makes the period unreplayable under the policy then in force however well the
     *       figures agree.</li>
     * </ul>
     *
     * <p>Nothing in {@link ReplayRun} or {@link ClosedPeriod} guards any of those away. The two
     * runs are supplied independently, no constructor compares them, and the registry is consulted
     * rather than trusted.
     *
     * <p><b>Why the deviation is a count.</b> Three reasons, and the third is the one that settles
     * it. A period publishes many figures and several can differ, with a remedy per figure — so a
     * single money amount would have to be an aggregate, and {@code InvariantResult.conjunction}'s
     * rule about signed sums applies with full force: two figures wrong in opposite directions must
     * not net to a pass. Second, the count is what the nightly control can act on: it is the number
     * of things to look at. Third and decisively, the difference this control exists to catch has
     * <em>no money size</em> — a scale-only difference is zero rupees and is still a different
     * published artefact, so an amount-valued deviation would report the signature DT-1 breach as
     * nil and the result would read as a pass with a fail flag.
     *
     * <p><b>Why a policy divergence is inside the same count.</b> It is not a figure, and the
     * requirement's phrasing is a count of differing figures — but a DT-1 failure carrying a
     * deviation of zero is unreadable, and that is exactly what a version divergence on
     * bit-identical figures would produce. Any caller ranking breaches by size, or filtering
     * {@code deviation.signum() != 0}, would drop the most serious finding in the set. So the
     * deviation is the count of <em>discrepancies</em>: figures that differ plus policy kinds that
     * diverge, each capped at one per figure and one per kind, both with a per-item remedy. The
     * split is stated in the detail and available through {@link #figureDiscrepancyCount()} and
     * {@link #policyDiscrepancyCount()}, because "the numbers moved" and "the rule moved" send the
     * investigation to different places.
     *
     * <p>Published under DT-1 alone. Nothing here publishes {@link InvariantId#PV_1}, which is the
     * companion precondition asserted by the registry: {@code policyResolvableOn}'s javadoc warns
     * explicitly that its results must not be conjoined with a bit-identical-replay result, because
     * conjunction keeps the first breach's deviation among results sharing an id and the two
     * deviations count different things.
     */
    public InvariantResult dtOne() {
        String detail = describe();
        if (discrepancies.isEmpty()) {
            return InvariantResult.pass(InvariantId.DT_1, detail);
        }
        return InvariantResult.fail(
            InvariantId.DT_1, detail, BigDecimal.valueOf(discrepancies.size()));
    }

    /**
     * The DT-1 detail: what was compared, what differed, and the first few findings by name.
     *
     * <p>The counts of what was <em>compared</em> lead, ahead of the counts of what differed,
     * because a pass over nothing reads exactly like a pass over ten thousand figures unless the
     * denominator is on the line. See {@link #isVacuous()}.
     */
    public String describe() {
        StringBuilder sentence = new StringBuilder()
            .append(period.describe())
            .append(": replay ").append(replayed.runId())
            .append(" of ").append(published.runId())
            .append(" — ").append(figuresCompared()).append(" figures and ")
            .append(policyKindsCompared()).append(" policy kinds compared against the policy in")
            .append(" force at ").append(period.policyResolutionDate());
        if (discrepancies.isEmpty()) {
            return sentence.append("; bit-identical").toString();
        }
        sentence.append("; ").append(figureDiscrepancyCount()).append(" figure and ")
            .append(policyDiscrepancyCount()).append(" policy discrepancies: ");
        int named = 0;
        for (ReplayDiscrepancy finding : discrepancies) {
            if (named == FINDINGS_NAMED_IN_DETAIL) {
                sentence.append("; and ").append(discrepancies.size() - named).append(" more");
                break;
            }
            if (named > 0) {
                sentence.append("; ");
            }
            sentence.append(finding.describe());
            named++;
        }
        return sentence.toString();
    }

    /** Every finding, figures first in key order, then policy kinds in declaration order. */
    public List<ReplayDiscrepancy> discrepancies() {
        return discrepancies;
    }

    /** The findings of one kind — for a caller that wants the scale drifts on their own. */
    public List<ReplayDiscrepancy> discrepanciesOf(DiscrepancyKind kind) {
        Objects.requireNonNull(kind, "kind");
        return discrepancies.stream().filter(finding -> finding.kind() == kind).toList();
    }

    /** Whether the replay reproduced the period bit-identically under the policy then in force. */
    public boolean isBitIdentical() {
        return discrepancies.isEmpty();
    }

    /** The whole deviation: figures plus policy kinds. See {@link #dtOne()}. */
    public int discrepancyCount() {
        return discrepancies.size();
    }

    public int figureDiscrepancyCount() {
        return (int) discrepancies.stream().filter(finding -> !finding.isPolicyFinding()).count();
    }

    public int policyDiscrepancyCount() {
        return (int) discrepancies.stream().filter(ReplayDiscrepancy::isPolicyFinding).count();
    }

    /** How many distinct figure keys were compared — the denominator of the figure leg. */
    public int figuresCompared() {
        Set<String> keys = new TreeSet<>(published.figureKeys());
        keys.addAll(replayed.figureKeys());
        return keys.size();
    }

    /** How many policy kinds either run consulted — the denominator of the policy leg. */
    public int policyKindsCompared() {
        Set<PolicyKind> kinds = new LinkedHashSet<>(published.consultedKinds());
        kinds.addAll(replayed.consultedKinds());
        return kinds.size();
    }

    /**
     * Whether this comparison compared nothing at all, on either leg.
     *
     * <p>Exposed rather than refused at construction, and not folded into the DT-1 result. A run
     * that published no figures and cited no policy is a real state — a period with no contracts in
     * a newly opened book — and refusing it would put a guard where a fact belongs. But a pass over
     * an empty comparison is a tautology wearing an invariant id, which is the defect this codebase
     * has found four times, so the fact has to be visible: {@link #describe()} always states the
     * two denominators, and a caller assembling C-12 evidence should treat a vacuous comparison as
     * a control that did not run rather than as one that passed.
     */
    public boolean isVacuous() {
        return figuresCompared() == 0 && policyKindsCompared() == 0;
    }

    /** The period compared. */
    public ClosedPeriod period() {
        return period;
    }

    /** The run whose figures were published — DT-1's reference. */
    public ReplayRun publishedRun() {
        return published;
    }

    /** The replay produced by control C-12. */
    public ReplayRun replayedRun() {
        return replayed;
    }

    /**
     * What the registry says governed the period end, per kind.
     *
     * <p>Held on the comparison rather than recomputed by a reader, because it is evidence: the
     * whole claim of FR-903's second half is that this snapshot, and not today's, is what the
     * replay resolved against.
     */
    public Map<PolicyKind, String> policyInForceAtPeriodEnd() {
        return policyInForceAtPeriodEnd;
    }
}
