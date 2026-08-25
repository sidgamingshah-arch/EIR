package com.crisil.eir.policy.fee.rule;

import com.crisil.eir.policy.PolicyVersion;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a fee or cost posting to one of the five treatments of FR-201, or refuses and names the
 * key it could not resolve (FR-202).
 *
 * <p>This is the thing {@link com.crisil.eir.domain.FeeClassification}'s javadoc points at when it
 * says resolution "is a versioned rule set, not code", and it produces what
 * {@link com.crisil.eir.calc.projection.FeePosting} consumes: that record takes an
 * <em>already-resolved</em> classification and deliberately declines to re-decide it, so this is
 * the only place the decision is made.
 *
 * <h2>Two clocks, and both of them matter</h2>
 *
 * <p>Effective-dating happens at two levels and they are not the same question:
 *
 * <ol>
 *   <li><b>Which version of the taxonomy is in force</b> on the date being asked about. A
 *       resolver holds one or many {@link FeeRuleSet} versions; the one with the latest
 *       {@link PolicyVersion#effectiveFrom()} that is operative and not in the future governs.
 *       {@code SUPERSEDED} counts as operative
 *       ({@link com.crisil.eir.policy.PolicyVersionStatus#isOperative()}) precisely so that a
 *       replay of a closed period resolves against the reading that was in force when it closed
 *       rather than today's — invariant DT-1.
 *   <li><b>Which rule inside that version</b> governs, by {@link FeeRule#PRECEDENCE}: most
 *       specific first, later effective date breaking ties within a shape.
 * </ol>
 *
 * <p>Selecting by date is right for a live run and not enough for a replay, because a correction
 * can take effect inside a period that has already closed. {@link #resolveAgainstVersion} pins
 * resolution to the {@code rule_set_version_id} the computation stored, which is what makes the
 * replay reproduce the figure that was published rather than the one policy would give today.
 *
 * <p>An {@code APPROVED}-but-not-yet-{@code EFFECTIVE} version can be held here safely: it is not
 * operative, so it resolves nothing until its date arrives. That is the whole reason
 * {@code PolicyVersionStatus} distinguishes the two — a version approved in March to take effect
 * in April must not classify a March posting — and holding it rather than rejecting it at
 * construction is what lets an impact preview be computed against a pending version.
 *
 * <h2>The refusal</h2>
 *
 * <p>Three distinguishable ways a lookup fails, and the refusal says which:
 *
 * <ul>
 *   <li>no version of the taxonomy is in force on the date — a gap in the policy register;
 *   <li>the version in force states no rule for the code at all — a gap in the fee master;
 *   <li>it states rules for the code but none covering this product, entity and date — a gap in a
 *       product rollout, and the most common of the three in practice, because extending a
 *       product is a different team's change from writing a fee code.
 * </ul>
 *
 * <p>None of them returns a treatment. See
 * {@link FeeClassificationResolution} for why both available defaults are wrong.
 */
public final class FeeClassificationResolver {

    /**
     * How many stated rules a refusal enumerates before summarising the rest.
     *
     * <p>An exception-queue entry is read by a person. A code carrying four hundred rules would
     * produce a refusal nobody reads, which is the same failure as no refusal at all. Bounded, and
     * bounded deterministically: the rules are sorted before truncation, so the same set always
     * yields the same message.
     */
    private static final int REFUSAL_RULE_LIMIT = 8;

    /** Versions in ascending order of {@link PolicyVersion#effectiveFrom()}. */
    private final List<FeeRuleSet> versions;

    /** A resolver over a single version of the taxonomy — the ordinary case for one run. */
    public FeeClassificationResolver(FeeRuleSet ruleSet) {
        this(List.of(Objects.requireNonNull(ruleSet, "ruleSet")));
    }

    /**
     * A resolver over several versions, for a run that spans an effective date or replays a closed
     * period.
     *
     * <p>Two rejections at construction, both because they would make the choice of version
     * arbitrary rather than dated: a repeated version id (two different sets claiming one identity,
     * so a stored {@code rule_set_version_id} no longer identifies a reading), and two
     * <em>operative</em> versions sharing one {@code effectiveFrom} (nothing orders them, so which
     * governs would depend on argument order). Neither is a data condition arriving from a feed;
     * both are wiring defects in the policy register, so both throw rather than resolving to a
     * refusal.
     *
     * <p>The date collision is checked over operative versions only, and the restriction is
     * deliberate. A {@code DRAFT} or {@code APPROVED} version never competes in
     * {@link #ruleSetInForceOn}, so nothing is ambiguous about holding two of them on one date —
     * which is what a bank does while it revises a pending version, and the reason the register can
     * hold a pending version at all. Two <em>operative</em> readings claiming the same first day
     * stay rejected, because there is genuinely no answer: ordering them by approval date would be
     * a second clock nothing else in the engine reads, and picking one by status would let a
     * retrospective correction take effect without a date. Correct that case by restating through
     * {@link FeeRuleSet#withRule} under a later effective date.
     */
    public FeeClassificationResolver(List<FeeRuleSet> ruleSets) {
        Objects.requireNonNull(ruleSets, "ruleSets");
        if (ruleSets.isEmpty()) {
            throw new IllegalArgumentException(
                "a fee classification resolver needs at least one rule set version; a resolver over"
                    + " nothing would refuse every posting in the book with the same message and"
                    + " tell nobody why");
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (FeeRuleSet ruleSet : ruleSets) {
            Objects.requireNonNull(ruleSet, "ruleSet");
            PolicyVersion version = ruleSet.version();
            if (!ids.add(version.id())) {
                throw new IllegalArgumentException(
                    "fee rule set version id " + version.id() + " is registered twice. Two readings"
                        + " under one id make every posting classified by either indistinguishable"
                        + " on replay (04 § 2.6)");
            }
            if (version.status().isOperative() && !dates.add(version.effectiveFrom())) {
                throw new IllegalArgumentException(
                    "two operative fee rule set versions both take effect on "
                        + version.effectiveFrom() + " (" + version.id() + " among them). Nothing"
                        + " orders them, so which one classifies a posting would depend on"
                        + " registration order");
            }
        }
        List<FeeRuleSet> ordered = new ArrayList<>(ruleSets);
        ordered.sort(Comparator.comparing(ruleSet -> ruleSet.version().effectiveFrom()));
        this.versions = List.copyOf(ordered);
    }

    /** The registered versions, earliest effective first. */
    public List<FeeRuleSet> versions() {
        return versions;
    }

    /**
     * The version of the taxonomy in force on {@code asOf} — the latest operative one whose
     * effective date has arrived.
     *
     * <p>Empty where the date precedes every registered version, or where every registered version
     * is still {@code DRAFT} or {@code PENDING_APPROVAL}. Both are configuration gaps and both
     * surface as a refusal from {@link #resolve}, not as a silent absence.
     */
    public Optional<FeeRuleSet> ruleSetInForceOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        FeeRuleSet inForce = null;
        // Ascending order, so the last one that qualifies is the latest to take effect. Written as
        // a scan rather than a reverse search because the list is short — a bank holds a handful of
        // taxonomy versions, not thousands — and a scan cannot get the direction wrong.
        for (FeeRuleSet candidate : versions) {
            if (candidate.isEffectiveOn(asOf)) {
                inForce = candidate;
            }
        }
        return Optional.ofNullable(inForce);
    }

    /**
     * Resolves one posting's fee code against the taxonomy in force on {@code asOf}.
     *
     * @param feeCode the posting's fee code; required
     * @param product the product identifier, or null where the feed carries none
     * @param entity  the legal-entity identifier, or null where the feed carries none
     * @param asOf    the posting date, or the as-of date of a replay
     */
    public FeeClassificationResolution resolve(String feeCode, String product, String entity,
        LocalDate asOf) {
        return resolve(FeeRuleKey.query(feeCode, product, entity, asOf));
    }

    /**
     * Resolves one lookup key.
     *
     * <p>Total: every input either yields a classification or yields a refusal naming this key.
     * There is no third outcome and no fallback.
     */
    public FeeClassificationResolution resolve(FeeRuleKey lookup) {
        Objects.requireNonNull(lookup, "lookup");
        Optional<FeeRuleSet> inForce = ruleSetInForceOn(lookup.effectiveFrom());
        if (inForce.isEmpty()) {
            return FeeClassificationResolution.unmapped(lookup, noVersionInForceDetail(lookup));
        }
        return resolveWithin(inForce.get(), lookup);
    }

    /**
     * Resolves against one <em>named</em> version of the taxonomy — the replay entry point.
     *
     * <p>Date-based resolution answers "what governs a posting raised on this date", which is the
     * right question for a live run and the wrong one for a replay. A period closed under
     * {@code FEE-2027.1} and later corrected by {@code FEE-2027.2} effective inside that same
     * period would, resolved by date, come back classified by the correction — reproducing a figure
     * nobody published and failing invariant DT-1 in the one place it was meant to hold. Every
     * {@code EIR_COMPUTATION} row stores the {@code rule_set_version_id} that classified it
     * (04 § 2.6) precisely so the replay can pin it, so the operation exists here rather than being
     * left to a caller to assemble out of {@link #versions()}.
     *
     * <p>The version-level effective-date test is deliberately <em>not</em> applied: naming the id
     * is the caller asserting which reading applies, which is what the stored id means. Rule-level
     * dating inside that version still applies, because that is part of the reading. An unapproved
     * version is refused — no computation can ever have stored the id of a {@code DRAFT} or
     * {@code PENDING_APPROVAL} version, so a request to replay against one is a defect in the
     * caller rather than a fact about the book, and it throws instead of returning a refusal.
     *
     * @throws IllegalArgumentException if no version with that id is registered, or if it is
     *                                  unapproved
     */
    public FeeClassificationResolution resolveAgainstVersion(FeeRuleKey lookup, String ruleSetVersionId) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(ruleSetVersionId, "ruleSetVersionId");
        FeeRuleSet pinned = null;
        List<String> registered = new ArrayList<>(versions.size());
        for (FeeRuleSet candidate : versions) {
            registered.add(candidate.version().id());
            if (candidate.version().id().equals(ruleSetVersionId)) {
                pinned = candidate;
            }
        }
        if (pinned == null) {
            throw new IllegalArgumentException(
                "no fee rule set version " + ruleSetVersionId + " is registered; replay of a"
                    + " computation citing it is impossible (invariant DT-1). Registered: "
                    + registered);
        }
        if (!pinned.version().status().isApproved()) {
            throw new IllegalArgumentException(
                "fee rule set version " + ruleSetVersionId + " is " + pinned.version().status()
                    + " and cannot have classified anything; no computation can cite an unapproved"
                    + " version");
        }
        return resolveWithin(pinned, lookup);
    }

    /** Rule-level resolution inside one version — shared by the dated and the pinned entry points. */
    private static FeeClassificationResolution resolveWithin(FeeRuleSet ruleSet, FeeRuleKey lookup) {
        String versionId = ruleSet.version().id();
        List<FeeRule> stated = ruleSet.rulesForNormalised(lookup.feeCode());
        if (stated.isEmpty()) {
            return FeeClassificationResolution.unmapped(lookup,
                "fee rule set version " + versionId + " states no rule for fee code "
                    + lookup.feeCode() + ", looked up at " + lookup.describe()
                    + ". FR-202: the code fails into the exception queue rather than defaulting to"
                    + " either treatment — a gap in the fee master, not a classification");
        }
        List<FeeRule> candidates = new ArrayList<>(stated.size());
        for (FeeRule rule : stated) {
            if (rule.matches(lookup)) {
                candidates.add(rule);
            }
        }
        if (candidates.isEmpty()) {
            return FeeClassificationResolution.unmapped(lookup, uncoveredDetail(lookup, versionId, stated));
        }
        return FeeClassificationResolution.resolved(lookup, mostSpecific(candidates, versionId), versionId);
    }

    /**
     * The winner under {@link FeeRule#PRECEDENCE}.
     *
     * <p>The tie guard is unreachable by construction — {@link FeeRuleSet} rejects two rules on one
     * key, and equal precedence within one lookup's candidate set implies an equal key, as
     * {@code PRECEDENCE} documents. It is retained because that argument is a paragraph of
     * reasoning about wildcard matching, and if the reasoning is ever wrong the failure it admits
     * is the one FR-202 exists to prevent: an arbitrary choice between two treatments, decided by
     * iteration order, appearing in the ledger as an ordinary classification. A named
     * {@link IllegalStateException} is a rule-set defect a developer fixes; a silent choice is a
     * misstatement nobody finds.
     */
    private static FeeRule mostSpecific(List<FeeRule> candidates, String versionId) {
        FeeRule best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            FeeRule candidate = candidates.get(i);
            int comparison = FeeRule.PRECEDENCE.compare(candidate, best);
            if (comparison > 0) {
                best = candidate;
            } else if (comparison == 0) {
                throw new IllegalStateException(
                    "fee rule set version " + versionId + " has two rules of equal precedence for"
                        + " one lookup: " + best.key().describe() + " and "
                        + candidate.key().describe() + ". Resolution would depend on iteration"
                        + " order, which is the arbitrary choice between treatments FR-202 forbids");
            }
        }
        return best;
    }

    /** Why no version was in force, in terms a policy-register operator can act on. */
    private String noVersionInForceDetail(FeeRuleKey lookup) {
        LocalDate earliest = versions.get(0).version().effectiveFrom();
        int operative = 0;
        for (FeeRuleSet ruleSet : versions) {
            if (ruleSet.version().status().isOperative()) {
                operative++;
            }
        }
        return "no fee rule set version is in force on " + lookup.effectiveFrom() + ", looked up at "
            + lookup.describe() + ". " + versions.size() + " version(s) registered, " + operative
            + " operative, earliest effective " + earliest
            + ". FR-202: the posting fails into the exception queue rather than borrowing a reading"
            + " from a version that was not in force";
    }

    /**
     * Why the code's own rules did not cover this lookup — the third and commonest refusal.
     *
     * <p>Enumerates what the version does state for the code, sorted and truncated, because the
     * operator's next question is always "then what is configured?" and the answer decides whether
     * the fix is a product rollout, an entity carve-out or a date.
     */
    private static String uncoveredDetail(FeeRuleKey lookup, String versionId, List<FeeRule> stated) {
        List<String> described = new ArrayList<>(stated.size());
        for (FeeRule rule : stated) {
            described.add(rule.key().describe());
        }
        described.sort(Comparator.naturalOrder());
        String listed = String.join(", ", described.subList(0, Math.min(REFUSAL_RULE_LIMIT, described.size())));
        String suffix = described.size() > REFUSAL_RULE_LIMIT
            ? " and " + (described.size() - REFUSAL_RULE_LIMIT) + " more"
            : "";
        return "fee rule set version " + versionId + " states " + stated.size() + " rule(s) for fee"
            + " code " + lookup.feeCode() + ", none covering " + lookup.describe()
            + ". Stated: " + listed + suffix
            + ". FR-202: the posting fails into the exception queue rather than falling through to"
            + " the nearest available rule";
    }
}
