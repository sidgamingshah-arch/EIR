package com.crisil.eir.policy.fee.rule;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * One approved version of the fee and cost taxonomy: a {@link PolicyVersion} of kind
 * {@link PolicyKind#FEE_RULE_SET} and the {@link FeeRule} rows it approves (FR-201, 03 § 3.2,
 * 04 § 2.5).
 *
 * <p>Immutable once constructed, because 03 § 3.2 says rule sets are immutable once approved and
 * because the alternative is unfalsifiable: every {@code EIR_COMPUTATION} row stores the
 * {@code rule_set_version_id} that classified its postings (04 § 2.6), and a set that can be
 * edited behind that reference makes a closed period irreproducible — invariant DT-1 fails and
 * nothing says why. A change is {@link #withRule}, which demands a new version.
 *
 * <p>Not a record, unlike most of its neighbours, because it holds a lookup index. A production
 * fee master runs to several hundred codes; a linear scan of the rule list per posting, over a
 * ten-million-contract run with several postings each, is billions of comparisons for an answer a
 * hash lookup gives directly. The index is by fee code — the one component of the key that is
 * never wildcarded, and therefore the only one that partitions the rules.
 *
 * <h2>What the constructor refuses, and the one thing it does not</h2>
 *
 * <p>It refuses a {@link PolicyVersion} of the wrong {@link PolicyKind}: a routing table version
 * cannot classify a fee, and a rule set carrying a {@code ROUTING_TABLE} id would cite, on every
 * posting it classified, a version whose approval was about something else entirely. It refuses
 * two rules sharing one {@link FeeRuleKey} — that is what makes {@link FeeRule#PRECEDENCE} total,
 * so the refusal is load-bearing rather than tidiness.
 *
 * <p>It does <b>not</b> refuse a fee code lacking the mandatory per-code default of 04 § 2.5. Two
 * reasons. A partially-populated set is the normal state of a taxonomy under construction — the
 * longest-lead item in the programme (roadmap Phase 2 risk register) — and refusing to represent
 * one would mean the incomplete state could not be reviewed, previewed or approved against.
 * Second, incompleteness already has a correct behaviour: the codes and combinations it does not
 * cover refuse into the exception queue under FR-202, which is the designed outcome and not a
 * degraded one. Completeness is therefore reported, by {@link #feeCodesWithoutCatchAll()}, for
 * the FR-210 approval gate to make mandatory at the point where "mandatory" means something —
 * approval — rather than at construction, where it would only mean the gap is invisible.
 */
public final class FeeRuleSet {

    private final PolicyVersion version;
    private final List<FeeRule> rules;
    private final Map<String, List<FeeRule>> byFeeCode;

    /**
     * @param version the approval trail; must be of kind {@link PolicyKind#FEE_RULE_SET}
     * @param rules   the approved rows, in any order; copied
     */
    public FeeRuleSet(PolicyVersion version, List<FeeRule> rules) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(rules, "rules");
        if (version.kind() != PolicyKind.FEE_RULE_SET) {
            throw new IllegalArgumentException(
                "policy version " + version.id() + " is of kind " + version.kind()
                    + " and cannot approve a fee rule set. Every posting this set classifies would"
                    + " cite an approval that was about something else (04 § 2.6)");
        }
        // A duplicate DETECTOR, not a deduplicator — a repeated key is rejected below, never
        // silently collapsed, and that rejection is what makes FeeRule.PRECEDENCE total. Insertion
        // ordered so that the first duplicate reported is the first one in the caller's own
        // ordering, and so that a describe() or an exception-queue entry reads the same on every
        // run: deterministic text is part of what makes an exception a record.
        Map<FeeRuleKey, FeeRule> byKey = new LinkedHashMap<>();
        for (FeeRule rule : rules) {
            Objects.requireNonNull(rule, "rule");
            FeeRule existing = byKey.putIfAbsent(rule.key(), rule);
            if (existing != null) {
                throw new IllegalArgumentException(
                    "fee rule set " + version.id() + " states key " + rule.key().describe()
                        + " twice: " + existing.classification() + " and " + rule.classification()
                        + ". Two rules on one key have no precedence between them, so resolution"
                        + " would depend on iteration order — and FR-202 forbids exactly this kind"
                        + " of silent choice between treatments");
            }
        }
        this.version = version;
        this.rules = List.copyOf(byKey.values());
        Map<String, List<FeeRule>> index = new LinkedHashMap<>();
        for (FeeRule rule : this.rules) {
            index.computeIfAbsent(rule.feeCode(), code -> new ArrayList<>()).add(rule);
        }
        index.replaceAll((code, forCode) -> List.copyOf(forCode));
        this.byFeeCode = Collections.unmodifiableMap(index);
    }

    /** The approval trail this set was approved under; cited by every posting it classifies. */
    public PolicyVersion version() {
        return version;
    }

    /** The approved rows, in the order given; a repeated key was rejected at construction. */
    public List<FeeRule> rules() {
        return rules;
    }

    /** Whether this set states any rule at all for {@code feeCode}. */
    public boolean mapsFeeCode(String feeCode) {
        return !rulesFor(feeCode).isEmpty();
    }

    /**
     * Every rule stated for {@code feeCode}, of any shape and any date.
     *
     * <p>The resolver's candidate pool before matching and precedence. Note that a non-empty
     * result is <em>not</em> a promise that a lookup will resolve: rules only for
     * {@code (code, HOME_LOAN, *)} leave a posting on a personal loan unresolved, and that is a
     * refusal under FR-202 rather than a fall-through to the nearest available row.
     *
     * <p>The code is normalised the same way {@link FeeRuleKey} normalises it, so a lookup with
     * {@code "proc_fee"} finds rules stated as {@code "PROC_FEE"}.
     */
    public List<FeeRule> rulesFor(String feeCode) {
        Objects.requireNonNull(feeCode, "feeCode");
        return rulesForNormalised(FeeRuleKey.normaliseLookupCode(feeCode));
    }

    /**
     * The same lookup for a code {@link FeeRuleKey} has already normalised.
     *
     * <p>Exists to keep the hot path free of work already done. This is the ten-million-contract
     * path the index exists for, and {@link FeeClassificationResolver#resolve(FeeRuleKey)} arrives
     * holding a key whose code was normalised when the key was built; re-trimming and re-upcasing
     * it per posting is pure waste.
     */
    List<FeeRule> rulesForNormalised(String normalisedFeeCode) {
        return byFeeCode.getOrDefault(normalisedFeeCode, List.of());
    }

    /** Every fee code this set states a rule for, in the order the rules were given. */
    public Set<String> feeCodes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byFeeCode.keySet()));
    }

    /**
     * The fee codes with no {@code (code, *, *)} default — 04 § 2.5's mandatory-default control.
     *
     * <p>Asked of this version's own {@link PolicyVersion#effectiveFrom()} — the FR-210 approval
     * gate's question is "on the day this goes live, which codes will queue exceptions?", and a row
     * dated after that day does not answer it. See {@link #feeCodesWithoutCatchAll(LocalDate)} to
     * ask about another date.
     *
     * <p>Reported rather than refused; see the class comment for why. What the gate does with a
     * non-empty list is its own decision, but the list is the quantified form of "how much of this
     * taxonomy is still going to raise exceptions", which is the question the mandatory impact
     * preview exists to answer before a version goes effective.
     */
    public List<String> feeCodesWithoutCatchAll() {
        return feeCodesWithoutCatchAll(version.effectiveFrom());
    }

    /**
     * The same control asked of a particular date: fee codes with no {@code (code, *, *)} row
     * <em>in force on</em> {@code asOf}.
     *
     * <p>The date is not decoration; a date-blind form of this control is a hole. A code whose only
     * default is dated 1 January 2030 has a default, and would report as complete on a set
     * effective April 2027 — while every posting of that code between the two dates refuses with
     * {@code UNMAPPED_FEE_CODE}. The
     * approval gate would then pass a version that queues an exception for every posting of that
     * code, on the strength of a control that looked at the row and not at when it starts.
     *
     * <p>Sorted, not insertion-ordered: this one is read by a person comparing two runs, and a
     * diff of two alphabetical lists is legible where a diff of two insertion orders is not.
     */
    /**
     * Per-code completeness as the assertion it supports — invariant
     * {@link com.crisil.eir.domain.InvariantId#RS_1}.
     *
     * <p>Reported rather than refused at construction, and that stays true: a partially-loaded
     * taxonomy is a real state during the months-long sourcing exercise 08 § 0 calls the
     * programme's critical path, and refusing it would make the gap invisible rather than absent.
     * What changes is that the gap is now <em>asserted</em>, so the FR-210 approval gate can make
     * it mandatory at the point where "mandatory" means something.
     *
     * <p>Deviation is the count of codes lacking a per-code default, which is directly the number
     * of fee codes that will raise {@code UNMAPPED_FEE_CODE} on any product or entity nobody has
     * written a carve-out for. That is the quantified form of "how much of this taxonomy still
     * raises exceptions" — the figure an impact preview exists to carry.
     */
    public InvariantResult catchAllCoverage(LocalDate asOf) {
        List<String> gaps = feeCodesWithoutCatchAll(asOf);
        String detail = version.id() + " as at " + asOf + ": "
            + (gaps.isEmpty()
                ? "every fee code has a per-code default in force"
                : gaps.size() + " fee code(s) with no per-code default: " + gaps);
        return gaps.isEmpty()
            ? InvariantResult.pass(InvariantId.RS_1, detail)
            : InvariantResult.fail(
                InvariantId.RS_1, detail, java.math.BigDecimal.valueOf(gaps.size()));
    }

    public List<String> feeCodesWithoutCatchAll(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        List<String> incomplete = new ArrayList<>();
        for (Map.Entry<String, List<FeeRule>> entry : new TreeMap<>(byFeeCode).entrySet()) {
            boolean hasDefault = false;
            for (FeeRule rule : entry.getValue()) {
                if (rule.isCatchAll() && !rule.key().effectiveFrom().isAfter(asOf)) {
                    hasDefault = true;
                    break;
                }
            }
            if (!hasDefault) {
                incomplete.add(entry.getKey());
            }
        }
        return List.copyOf(incomplete);
    }

    /** Whether this version of the taxonomy governs {@code asOf} — {@link PolicyVersion#isEffectiveOn}. */
    public boolean isEffectiveOn(LocalDate asOf) {
        return version.isEffectiveOn(asOf);
    }

    /**
     * A copy of this set with one rule added or restated, under a new version.
     *
     * <p>The new version is a required argument rather than derived, because that is the governed
     * step: a change to the taxonomy is a maker–checker event with a stored impact preview
     * (FR-210, 03 § 3.2). Re-using the current version's id is rejected for the reason
     * {@code RoutingTable.reroute} rejects it — two different rule sets claiming one identity make
     * every posting classified under either indistinguishable on replay.
     *
     * <p>Restating an existing key replaces it in place rather than raising the duplicate-key
     * rejection, and keeps its position in the list. Adding a rule under a <em>later</em> effective
     * date is a different act with a different meaning: it supersedes by date and both rows stay,
     * which is what {@link FeeRule#PRECEDENCE} reads. Use the restatement to correct a row that
     * was wrong; use a later-dated row to reprice.
     */
    public FeeRuleSet withRule(FeeRule rule, PolicyVersion newVersion) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(newVersion, "newVersion");
        if (newVersion.id().equals(version.id())) {
            throw new IllegalArgumentException(
                "a fee rule change is a new version, not an edit; version id " + version.id()
                    + " is already in use and is cited by every posting it has classified");
        }
        List<FeeRule> revised = new ArrayList<>(rules.size() + 1);
        boolean replaced = false;
        for (FeeRule existing : rules) {
            if (existing.key().equals(rule.key())) {
                revised.add(rule);
                replaced = true;
            } else {
                revised.add(existing);
            }
        }
        if (!replaced) {
            revised.add(rule);
        }
        return new FeeRuleSet(newVersion, revised);
    }

    /** One audit line: the version, its approval, and how much taxonomy it carries. */
    public String describe() {
        return version.describe() + " — " + rules.size() + " rule(s) over " + byFeeCode.size()
            + " fee code(s)";
    }

    @Override
    public String toString() {
        return describe();
    }
}
