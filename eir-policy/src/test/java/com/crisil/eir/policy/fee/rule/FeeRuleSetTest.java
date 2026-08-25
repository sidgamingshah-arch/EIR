package com.crisil.eir.policy.fee.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rule set as a versioned, immutable artefact (03 § 3.2, 04 § 2.5) — what it refuses to
 * represent, and the one incompleteness it reports rather than refuses.
 */
class FeeRuleSetTest {

    private static final LocalDate APRIL_2027 = FeeRuleFixtures.APRIL_2027;

    @Nested
    @DisplayName("what the constructor refuses")
    class Refusals {

        @Test
        @DisplayName("two rules on one key are refused, because that is what makes precedence total")
        void duplicateKeyIsRefused() {
            // Load-bearing, not tidiness. PRECEDENCE separates candidates by specificity and then
            // by date; two rules sharing a whole key agree on both, so nothing orders them and the
            // resolver would return whichever the iteration reached first. That is precisely the
            // arbitrary choice between treatments FR-202 forbids.
            FeeRule integral = FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                FeeClassification.INTEGRAL, "one reading");
            FeeRule asIncurred = FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                FeeClassification.AS_INCURRED, "the opposite reading, same key");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027), List.of(integral, asIncurred)))
                .withMessageContaining("twice")
                .withMessageContaining("INTEGRAL")
                .withMessageContaining("AS_INCURRED");
        }

        @Test
        @DisplayName("a routing table version cannot approve a fee taxonomy")
        void wrongPolicyKindIsRefused() {
            // Every posting stores the rule_set_version_id that classified it (04 § 2.6). A set
            // carrying a ROUTING_TABLE id would have each of its postings cite an approval that was
            // about the B5.4.5 reading — an audit trail pointing at the wrong decision is worse
            // than none, because it looks complete.
            PolicyVersion routing = new PolicyVersion(
                "RT-2027.1", PolicyKind.ROUTING_TABLE, "IASB ED on B5.4.5", APRIL_2027,
                "maker", "checker", APRIL_2027.minusDays(1), PolicyVersionStatus.EFFECTIVE);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeRuleSet(routing, List.of()))
                .withMessageContaining("cannot approve a fee rule set");
        }

        @Test
        @DisplayName("an unexplained rule is refused")
        void blankRationaleIsRefused() {
            // Same reason PolicyVersion refuses a version with no description. ACPIR 52 carries no
            // negative list, so every classification outside origination and commitment fees is the
            // bank electing IFRS 9 B5.4.2/B5.4.3 as policy. The rationale is where the election is
            // written down, one row at a time.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeRule.catchAll(
                    FeeRuleFixtures.PROC_FEE, APRIL_2027, FeeClassification.INTEGRAL, "  "))
                .withMessageContaining("no")
                .withMessageContaining("rationale");
        }

        @Test
        @DisplayName("EXCLUDED_BY_DIRECTION is a legitimate rule outcome and is not refused")
        void penalChargeClassificationIsRepresentable() {
            // 03 § 3.4 puts the penal-charge exclusion at the ingestion boundary, "not a judgement
            // in the rule set" — but the boundary filter can only fire on postings whose code
            // RESOLVES to EXCLUDED_BY_DIRECTION, so the rule set is exactly where that has to be
            // expressible. The rule set says which codes are penal charges; FeePosting refuses to
            // let them through.
            FeeRule penal = FeeRule.catchAll("PENAL_CHG", APRIL_2027,
                FeeClassification.EXCLUDED_BY_DIRECTION,
                "RBI 2023 framework: a charge, not penal interest; not capitalised (invariant PC-1)");
            assertThat(penal.classification().entersCarryingAmount())
                .as("and it never enters the carrying amount")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("immutability, because a stored version id must mean one thing forever")
    class Immutability {

        @Test
        @DisplayName("the constructor copies, so mutating the caller's list changes nothing")
        void constructorCopiesTheRuleList() {
            // Without the copy, a set could be edited behind a rule_set_version_id that thousands
            // of closed-period computations already cite. The period would then be irreproducible
            // (invariant DT-1) and nothing would say why — the version id still matches.
            List<FeeRule> mutable = new ArrayList<>();
            mutable.add(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                FeeClassification.INTEGRAL, "origination fee, ACPIR 52 positive limb"));
            FeeRuleSet ruleSet = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027), mutable);

            mutable.add(FeeRule.catchAll("LEGAL_FEE", APRIL_2027,
                FeeClassification.AS_INCURRED, "added after approval"));

            assertThat(ruleSet.rules()).as("one rule at construction, one rule now").hasSize(1);
            assertThat(ruleSet.feeCodes())
                .as("the code added afterwards never entered the approved set")
                .containsExactly(FeeRuleFixtures.PROC_FEE);
        }
    }

    @Nested
    @DisplayName("a change is a new version, not an edit")
    class Versioning {

        @Test
        @DisplayName("re-using the current version id is refused")
        void withRuleDemandsANewVersion() {
            FeeRuleSet base = FeeRuleFixtures.fourRanks();
            FeeRule addition = FeeRule.catchAll("LEGAL_FEE", APRIL_2027,
                FeeClassification.AS_INCURRED, "external legal recharge, B5.4.3 exclusion");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> base.withRule(addition, base.version()))
                .withMessageContaining("is already in use");
        }

        @Test
        @DisplayName("restating a key replaces that row; a later-dated key adds one")
        void restatementReplacesAndDatingAppends() {
            // Two different acts with two different meanings. A restatement corrects a row that was
            // wrong — one row in, one row out. A later-dated row reprices — both rows stay, and
            // PRECEDENCE reads the dates, which is what lets a closed period resolve against the
            // old row after the new one takes effect.
            FeeRuleSet base = FeeRuleFixtures.fourRanks();
            int baseSize = 4;
            assertThat(base.rules()).as("the fixture carries the four specificity shapes").hasSize(baseSize);

            FeeRuleSet corrected = base.withRule(
                FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                    FeeClassification.AS_INCURRED, "correction: the default row was wrong"),
                FeeRuleFixtures.approved("FEE-2027.2", APRIL_2027.plusMonths(1)));
            assertThat(corrected.rules())
                .as("a restatement of an existing key is one row in, one row out")
                .hasSize(baseSize);

            FeeRuleSet repriced = base.withRule(
                FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027.plusYears(1),
                    FeeClassification.AS_INCURRED, "repricing effective a year later"),
                FeeRuleFixtures.approved("FEE-2028.1", APRIL_2027.plusYears(1)));
            assertThat(repriced.rules())
                .as("a later-dated row supersedes by date, so both rows survive")
                .hasSize(baseSize + 1);
        }
    }

    @Nested
    @DisplayName("the mandatory per-code default is reported, not refused")
    class Completeness {

        @Test
        @DisplayName("codes lacking a (code, *, *) row are listed, sorted")
        void missingCatchAllsAreListed() {
            // 04 § 2.5 makes the per-code default mandatory. Reported here rather than refused at
            // construction for two reasons: a partially-populated taxonomy is the normal state of
            // the longest-lead item in the programme and has to be reviewable, and incompleteness
            // already has a correct behaviour — the combinations it does not cover refuse under
            // FR-202. "Mandatory" belongs at the FR-210 approval gate, where it can be enforced
            // against a quantified preview.
            FeeRuleSet partial = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(
                    FeeRule.catchAll("A_FEE", APRIL_2027, FeeClassification.INTEGRAL, "complete"),
                    FeeRule.forProduct("A_FEE", FeeRuleFixtures.HOME_LOAN, APRIL_2027,
                        FeeClassification.INTEGRAL, "and a narrower row"),
                    FeeRule.forProduct("Z_FEE", FeeRuleFixtures.HOME_LOAN, APRIL_2027,
                        FeeClassification.AS_INCURRED, "product row only, no default"),
                    FeeRule.forEntity("M_FEE", FeeRuleFixtures.BANK, APRIL_2027,
                        FeeClassification.SEPARATE_SERVICE, "entity row only, no default")));

            // Derived by reading the four rows: A_FEE has a catch-all, Z_FEE and M_FEE do not.
            // Sorted alphabetically so that a diff of two runs is legible.
            assertThat(partial.feeCodesWithoutCatchAll())
                .as("M_FEE and Z_FEE carry no per-code default; A_FEE does")
                .containsExactly("M_FEE", "Z_FEE");
        }

        @Test
        @DisplayName("a future-dated default does not make a code complete today")
        void futureDatedCatchAllIsNotYetADefault() {
            // The hole the date-less form of this control had. A code whose only default starts in
            // 2030 has a default row, and reads as complete on a set effective 2027 — while every
            // posting of that code between the two dates refuses with UNMAPPED_FEE_CODE. The
            // approval gate would pass a version that queues an exception for every posting of that
            // code, on the strength of a control that looked at the row and not at when it starts.
            LocalDate twentyThirty = LocalDate.of(2030, 1, 1);
            FeeRuleSet futureDefault = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(FeeRule.catchAll("F_FEE", twentyThirty,
                    FeeClassification.INTEGRAL, "a default that has not started yet")));

            assertThat(futureDefault.feeCodesWithoutCatchAll())
                .as("on the day the version goes live, F_FEE has no default in force")
                .containsExactly("F_FEE");
            assertThat(futureDefault.feeCodesWithoutCatchAll(twentyThirty))
                .as("and from 1 January 2030 it does")
                .isEmpty();
        }

        @Test
        @DisplayName("a fully defaulted set reports nothing outstanding")
        void completeSetIsEmpty() {
            assertThat(FeeRuleFixtures.fourRanks().feeCodesWithoutCatchAll())
                .as("the fixture includes the rank 0 default for its only code")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("lookup helpers")
    class Lookup {

        @Test
        @DisplayName("rulesFor normalises the code, and a non-empty result is not a promise")
        void rulesForNormalisesAndDoesNotPromise() {
            FeeRuleSet ruleSet = FeeRuleFixtures.fourRanks();
            assertThat(ruleSet.rulesFor("proc_fee"))
                .as("a lookup in the case the core banking export happened to use")
                .hasSize(4);
            assertThat(ruleSet.mapsFeeCode("LEGAL_FEE"))
                .as("a code the taxonomy has never heard of")
                .isFalse();
            assertThat(ruleSet.rulesFor("LEGAL_FEE")).isEmpty();
        }

        @Test
        @DisplayName("asking about an unusable code answers 'no' rather than throwing")
        void unusableCodeIsAnsweredNotRefused() {
            // A predicate that exists to say "no" must not throw on the input it says "no" to. The
            // caller here is screening feed data, where a blank fee code is a thing that arrives;
            // constructing a KEY from one is a defect and still raises.
            FeeRuleSet ruleSet = FeeRuleFixtures.fourRanks();
            assertThat(ruleSet.mapsFeeCode("   ")).isFalse();
            assertThat(ruleSet.rulesFor("*")).isEmpty();
        }

        @Test
        @DisplayName("the returned collections are unmodifiable")
        void collectionsAreUnmodifiable() {
            FeeRuleSet ruleSet = FeeRuleFixtures.fourRanks();
            assertThatThrownBy(() -> ruleSet.rules().add(
                FeeRule.catchAll("LEGAL_FEE", APRIL_2027, FeeClassification.AS_INCURRED, "x")))
                .as("an approved set cannot be extended through a getter")
                .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ruleSet.feeCodes().add("LEGAL_FEE"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("describe() names the version, its approval and the size of the taxonomy")
        void describeIsAnAuditLine() {
            assertThat(FeeRuleFixtures.fourRanks().describe())
                .contains("FEE-2027.1")
                .contains("accounting.policy.owner")
                .contains("4 rule(s) over 1 fee code(s)");
        }
    }
}
