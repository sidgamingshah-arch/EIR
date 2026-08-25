package com.crisil.eir.policy.fee.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-201 and FR-202: every posting resolves to one of five treatments, or fails into the exception
 * queue naming the key it could not resolve.
 *
 * <p>The precedence tests read the resolved <em>classification</em> as the identifier of which rank
 * won, which works because {@link FeeRuleFixtures#fourRanks()} deliberately gives each of the four
 * specificity shapes a different treatment. Expected outcomes are derived by hand from the
 * documented order — specificity first, product worth more than entity, later date breaking ties
 * within a shape — and never by observing the resolver.
 */
class FeeClassificationResolverTest {

    private static final LocalDate APRIL_2027 = FeeRuleFixtures.APRIL_2027;
    private static final LocalDate JUNE_2027 = FeeRuleFixtures.JUNE_2027;
    private static final LocalDate OCTOBER_2027 = LocalDate.of(2027, 10, 1);

    @Nested
    @DisplayName("most-specific-wins, with product outranking entity")
    class Precedence {

        @Test
        @DisplayName("an exact (code, product, entity) rule beats all three broader shapes")
        void exactRuleWins() {
            // All four rules match this lookup: rank 3 exactly, rank 2 on the product, rank 1 on
            // the entity, rank 0 unconditionally. 3 > 2 > 1 > 0, so the exact carve-out governs,
            // and the fixture marks rank 3 with SEPARATE_SERVICE.
            FeeClassificationResolution resolution = FeeRuleFixtures.fourRankResolver().resolve(
                FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN, FeeRuleFixtures.BANK, JUNE_2027);

            assertThat(resolution.isResolved()).isTrue();
            assertThat(resolution.classification())
                .as("rank 3 marker: the exact carve-out")
                .isEqualTo(FeeClassification.SEPARATE_SERVICE);
            assertThat(resolution.rule().key().specificity()).isEqualTo(3);
            assertThat(resolution.ruleSetVersionId())
                .as("04 § 2.6 stores this on the computation, or replay is impossible")
                .isEqualTo("FEE-2027.1");
        }

        @Test
        @DisplayName("with the exact rule absent, the product rule beats the entity rule")
        void productOutranksEntity() {
            // The decision the four-part key forces and the only case where the ranking of product
            // against entity is observable: one lookup matched by a (code, product, *) rule and a
            // (code, *, entity) rule and by nothing more specific. Product wins — the
            // classification is a fact about what the fee is for, and the product fixes that, while
            // the entity dimension exists for the narrower jurisdictional carve-out. Rank 2 = 2,
            // rank 1 = 1.
            FeeRuleSet withoutExact = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(
                    FeeRule.forProduct(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                        APRIL_2027, FeeClassification.INTEGRAL, "rank 2 marker"),
                    FeeRule.forEntity(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.BANK,
                        APRIL_2027, FeeClassification.AS_INCURRED, "rank 1 marker")));

            assertThat(new FeeClassificationResolver(withoutExact)
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                    FeeRuleFixtures.BANK, JUNE_2027)
                .classification())
                .as("rank 2 marker: the product rule, not the entity rule")
                .isEqualTo(FeeClassification.INTEGRAL);
        }

        @Test
        @DisplayName("an entity rule governs where the product does not match")
        void entityRuleGovernsOffProduct() {
            // Personal loan is named by no rule, so rank 3 and rank 2 drop out on the product and
            // the candidates are rank 1 (entity BANK_IN) and rank 0. 1 > 0.
            assertThat(FeeRuleFixtures.fourRankResolver()
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.PERSONAL_LOAN,
                    FeeRuleFixtures.BANK, JUNE_2027)
                .classification())
                .as("rank 1 marker: the entity override")
                .isEqualTo(FeeClassification.AS_INCURRED);
        }

        @Test
        @DisplayName("the per-code default answers where nothing narrower matches")
        void catchAllAnswersLast() {
            // Neither the product nor the entity is named by any rule, so only rank 0 is a
            // candidate — the mandatory per-code default of 04 § 2.5.
            assertThat(FeeRuleFixtures.fourRankResolver()
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.PERSONAL_LOAN,
                    FeeRuleFixtures.NBFC, JUNE_2027)
                .classification())
                .as("rank 0 marker: the per-code default")
                .isEqualTo(FeeClassification.OVER_COMMITMENT_PERIOD);
        }

        @Test
        @DisplayName("a posting carrying no product or entity resolves only against the default")
        void missingDimensionsFallToTheDefault() {
            // A feed that carries no product cannot claim a product-specific rule: a lookup
            // wildcard matches only a wildcard rule. So the answer is the rank 0 default, and a
            // code whose taxonomy has no default would refuse — which is the visible consequence
            // of a missing attribute, rather than a silently different classification.
            assertThat(FeeRuleFixtures.fourRankResolver()
                .resolve(FeeRuleFixtures.PROC_FEE, null, null, JUNE_2027)
                .classification())
                .as("rank 0 marker")
                .isEqualTo(FeeClassification.OVER_COMMITMENT_PERIOD);
        }
    }

    @Nested
    @DisplayName("effective dating: the same key resolves differently over time")
    class EffectiveDating {

        /**
         * One code, one shape, two dates: INTEGRAL from 1 April 2027, AS_INCURRED from 1 October.
         *
         * <p>The <em>version</em> takes effect on 1 January, earlier than either rule, so that the
         * date tests exercise rule-level dating rather than tripping over version-level dating
         * first. The two clocks are independent and the refusals they produce are different.
         */
        private FeeClassificationResolver repriced() {
            return new FeeClassificationResolver(new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", LocalDate.of(2027, 1, 1)),
                List.of(
                    FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                        FeeClassification.INTEGRAL, "origination fee, ACPIR 52 positive limb"),
                    FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, OCTOBER_2027,
                        FeeClassification.AS_INCURRED, "repriced as a servicing charge"))));
        }

        @Test
        @DisplayName("before the later date the earlier rule governs; on it, the later one does")
        void theLaterRuleTakesEffectOnItsOwnDate() {
            FeeClassificationResolver resolver = repriced();

            // 30 September 2027: the 1 October rule is in the future and is not a candidate, so the
            // 1 April rule is the only one.
            assertThat(resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null,
                    OCTOBER_2027.minusDays(1)).classification())
                .as("the day before the repricing")
                .isEqualTo(FeeClassification.INTEGRAL);

            // 1 October 2027: both are candidates, equal specificity, and the later date wins.
            assertThat(resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null, OCTOBER_2027)
                    .classification())
                .as("the repricing takes effect on its own date, inclusive")
                .isEqualTo(FeeClassification.AS_INCURRED);
        }

        @Test
        @DisplayName("before the earliest rule the code is unmapped, not defaulted backwards")
        void beforeTheEarliestRuleTheCodeRefuses() {
            // 31 March 2027. The code is in the taxonomy but nothing governs the date, and the
            // wrong answer here is the seductive one: reach for the earliest rule because it is
            // "obviously" what was meant. That would classify a posting under a policy that had
            // not been approved when it was raised.
            FeeClassificationResolution resolution = repriced()
                .resolve(FeeRuleFixtures.PROC_FEE, null, null, APRIL_2027.minusDays(1));

            assertThat(resolution.isResolved()).isFalse();
            assertThat(resolution.exception()).isEqualTo(ExceptionCategory.UNMAPPED_FEE_CODE);
            assertThat(resolution.detail())
                .as("names the code, the key and what the taxonomy does state")
                .contains("none covering (PROC_FEE, *, *, 2027-03-31)")
                .contains("2 rule(s) for fee code PROC_FEE");
        }

        @Test
        @DisplayName("specificity dominates recency: a new default does not override an old carve-out")
        void specificityBeatsALaterDefault() {
            // Not the obvious way round, and the case that decides the shape of the comparator. A
            // per-code default issued in 2028 does not displace a product carve-out written in
            // 2027: rank 2 > rank 0 whatever the dates, so the 2027 rule still governs home loans
            // in 2028. Superseding it takes a new rule at rank 2. The alternative order — newest
            // wins, specificity breaking ties — would have every broad default silently override
            // every narrow carve-out beneath it, and the carve-outs are the positions somebody
            // argued for.
            FeeRuleSet mixed = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(
                    FeeRule.forProduct(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                        APRIL_2027, FeeClassification.INTEGRAL, "rank 2, 2027"),
                    FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027.plusYears(1),
                        FeeClassification.AS_INCURRED, "rank 0, 2028")));

            assertThat(new FeeClassificationResolver(mixed)
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                    FeeRuleFixtures.BANK, LocalDate.of(2028, 6, 30))
                .classification())
                .as("the 2027 product rule still governs in 2028")
                .isEqualTo(FeeClassification.INTEGRAL);
        }
    }

    @Nested
    @DisplayName("FR-202: an unmapped code refuses and never defaults")
    class Unmapped {

        @Test
        @DisplayName("a code the taxonomy has never heard of yields no classification at all")
        void unknownCodeRefuses() {
            // The point of the unit. Both available defaults are wrong in the direction nobody
            // checks: INTEGRAL moves the fee into the gross carrying amount and spreads the error
            // across the life of the instrument as a small yield difference, AS_INCURRED keeps it
            // out and shows up as period-one income nobody questions. Neither breaks a
            // reconciliation and neither trips an invariant. On reference case 1, 5,000 of net fee
            // on a million moves the EIR 56.6 basis points, so the amount at stake is not small —
            // only the symptom is.
            FeeClassificationResolution resolution = FeeRuleFixtures.fourRankResolver()
                .resolve("BROKERAGE_RECOVERY", FeeRuleFixtures.HOME_LOAN,
                    FeeRuleFixtures.BANK, JUNE_2027);

            assertThat(resolution.isResolved()).isFalse();
            assertThat(resolution.classification())
                .as("no classification, and specifically neither of the two plausible defaults")
                .isNull();
            assertThat(resolution.classification())
                .isNotEqualTo(FeeClassification.INTEGRAL)
                .isNotEqualTo(FeeClassification.AS_INCURRED);
            assertThat(resolution.rule()).as("nothing to cite, so nothing is cited").isNull();
            assertThat(resolution.exception())
                .as("the category ExceptionCategory documents for exactly this")
                .isEqualTo(ExceptionCategory.UNMAPPED_FEE_CODE);
            assertThat(resolution.exception().stopsTheContract())
                .as("a hard stop for that contract, per FR-905")
                .isTrue();
            assertThat(resolution.detail())
                .as("the refusal names the key, so the operator knows which component to configure")
                .contains("BROKERAGE_RECOVERY")
                .contains("(BROKERAGE_RECOVERY, HOME_LOAN, BANK_IN, 2027-06-30)")
                .contains("FR-202");
        }

        @Test
        @DisplayName("reading a classification off a refusal throws and names the key")
        void requireClassificationRefusesToInvent() {
            FeeClassificationResolution resolution = FeeRuleFixtures.fourRankResolver()
                .resolve("BROKERAGE_RECOVERY", null, null, JUNE_2027);

            assertThatIllegalStateException()
                .isThrownBy(resolution::requireClassification)
                .withMessageContaining("BROKERAGE_RECOVERY")
                .withMessageContaining("both defaults are wrong");
        }

        @Test
        @DisplayName("entersCarryingAmount() is false on a refusal, and that is not an answer")
        void refusalDoesNotEnterTheCarryingAmount() {
            // False because there is no classification, not because the fee was judged non-integral.
            // A caller that treats it as an answer builds an initial carrying amount excluding a fee
            // that may well belong in it, and invariant IC-1 would then tie against the wrong
            // figure — the net cash flow it is compared with is built from the same mistake.
            FeeClassificationResolution resolution = FeeRuleFixtures.fourRankResolver()
                .resolve("BROKERAGE_RECOVERY", null, null, JUNE_2027);
            assertThat(resolution.entersCarryingAmount()).isFalse();
            assertThat(resolution.isResolved()).as("which is the field a caller must read").isFalse();
        }

        @Test
        @DisplayName("a mapped code with an uncovered combination refuses too, and says what is configured")
        void mappedCodeWithUncoveredCombinationRefuses() {
            // The commonest refusal in practice, and a different team's fix: the code exists and is
            // correctly classified for the products it was rolled out to, and a new product picked
            // it up without anyone extending the taxonomy. A fall-through to "the nearest available
            // rule" is what a rule engine without FR-202 would do here.
            FeeRuleSet productOnly = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(FeeRule.forProduct(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                    APRIL_2027, FeeClassification.INTEGRAL, "rolled out on home loans only")));

            FeeClassificationResolution resolution = new FeeClassificationResolver(productOnly)
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.PERSONAL_LOAN,
                    FeeRuleFixtures.BANK, JUNE_2027);

            assertThat(resolution.isResolved()).isFalse();
            assertThat(resolution.detail())
                .as("enumerates what is configured, so the operator can see the gap is the product")
                .contains("none covering (PROC_FEE, PERSONAL_LOAN, BANK_IN, 2027-06-30)")
                .contains("Stated: (PROC_FEE, HOME_LOAN, *, 2027-04-01)");
        }

        @Test
        @DisplayName("the three refusals are distinguishable, because three different teams fix them")
        void refusalsNameWhichGapItIs() {
            FeeClassificationResolver resolver = FeeRuleFixtures.fourRankResolver();

            assertThat(resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null,
                    APRIL_2027.minusDays(1)).detail())
                .as("no version in force: a gap in the policy register")
                .contains("no fee rule set version is in force on 2027-03-31");
            assertThat(resolver.resolve("BROKERAGE_RECOVERY", null, null, JUNE_2027).detail())
                .as("code unknown: a gap in the fee master")
                .contains("states no rule for fee code BROKERAGE_RECOVERY");
            assertThat(new FeeClassificationResolver(new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                    List.of(FeeRule.forProduct(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                        APRIL_2027, FeeClassification.INTEGRAL, "home loans only"))))
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.PERSONAL_LOAN, null, JUNE_2027)
                .detail())
                .as("combination uncovered: a gap in a product rollout")
                .contains("none covering");
        }
    }

    @Nested
    @DisplayName("which version of the taxonomy is in force")
    class VersionInForce {

        @Test
        @DisplayName("a date before the version's effective date resolves against nothing")
        void beforeTheVersionTakesEffect() {
            // PolicyVersion already separates approval from effect; this is the resolver honouring
            // it. A version approved in March to take effect on 1 April must not classify a March
            // posting, however complete its taxonomy.
            FeeClassificationResolution resolution = FeeRuleFixtures.fourRankResolver()
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                    FeeRuleFixtures.BANK, APRIL_2027.minusDays(1));

            assertThat(resolution.isResolved()).isFalse();
            assertThat(resolution.detail())
                .contains("no fee rule set version is in force on 2027-03-31")
                .contains("1 version(s) registered, 1 operative");
        }

        @Test
        @DisplayName("a DRAFT taxonomy classifies nothing, however complete")
        void draftIsNotInForce() {
            // isOperative() is EFFECTIVE or SUPERSEDED. A resolver holding only drafts refuses
            // every posting — loudly, into the exception queue, naming each key — rather than
            // resolving against an unapproved reading.
            FeeRuleSet draft = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.9", APRIL_2027, PolicyVersionStatus.DRAFT),
                FeeRuleFixtures.fourRanks().rules());

            FeeClassificationResolution resolution = new FeeClassificationResolver(draft)
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                    FeeRuleFixtures.BANK, JUNE_2027);

            assertThat(resolution.isResolved()).isFalse();
            assertThat(resolution.detail()).contains("1 version(s) registered, 0 operative");
        }

        @Test
        @DisplayName("a superseded version still classifies a closed period — invariant DT-1")
        void supersededVersionStillResolvesItsOwnPeriod() {
            // The whole reason SUPERSEDED counts as operative. FEE-2027.1 read the code as
            // INTEGRAL; FEE-2028.1 reads it as AS_INCURRED from 1 April 2028. A replay of June 2027
            // must reproduce INTEGRAL, or the closed period cannot be reproduced and nothing says
            // why.
            FeeClassificationResolver resolver = new FeeClassificationResolver(List.of(
                new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027, PolicyVersionStatus.SUPERSEDED),
                    List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                        FeeClassification.INTEGRAL, "the 2027 reading"))),
                new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2028.1", LocalDate.of(2028, 4, 1)),
                    List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, LocalDate.of(2028, 4, 1),
                        FeeClassification.AS_INCURRED, "the 2028 reading")))));

            FeeClassificationResolution replay =
                resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null, JUNE_2027);
            assertThat(replay.classification())
                .as("June 2027 resolves against the reading in force in June 2027")
                .isEqualTo(FeeClassification.INTEGRAL);
            assertThat(replay.ruleSetVersionId()).isEqualTo("FEE-2027.1");

            FeeClassificationResolution current =
                resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null, LocalDate.of(2028, 6, 30));
            assertThat(current.classification())
                .as("June 2028 resolves against the later version")
                .isEqualTo(FeeClassification.AS_INCURRED);
            assertThat(current.ruleSetVersionId()).isEqualTo("FEE-2028.1");

            // The boundary, both sides. 1 April 2028 is the later version's first day.
            assertThat(resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null,
                    LocalDate.of(2028, 3, 31)).ruleSetVersionId()).isEqualTo("FEE-2027.1");
            assertThat(resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null,
                    LocalDate.of(2028, 4, 1)).ruleSetVersionId()).isEqualTo("FEE-2028.1");
        }

        @Test
        @DisplayName("an APPROVED but not yet effective version can be held without classifying anything")
        void approvedFutureVersionIsInert() {
            // Which is what makes an impact preview computable: the pending version sits in the
            // register alongside the live one and resolves nothing until its date arrives. Here the
            // pending 2028 version is APPROVED rather than EFFECTIVE, so a 2028 lookup still lands
            // on the 2027 reading.
            FeeClassificationResolver resolver = new FeeClassificationResolver(List.of(
                new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                    List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                        FeeClassification.INTEGRAL, "the live reading"))),
                new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2028.1", LocalDate.of(2028, 4, 1),
                        PolicyVersionStatus.APPROVED),
                    List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, LocalDate.of(2028, 4, 1),
                        FeeClassification.AS_INCURRED, "signed off, not yet in force")))));

            assertThat(resolver.resolve(FeeRuleFixtures.PROC_FEE, null, null,
                    LocalDate.of(2028, 6, 30)).ruleSetVersionId())
                .as("APPROVED is not operative, so the live version still governs")
                .isEqualTo("FEE-2027.1");
        }

        @Test
        @DisplayName("ruleSetInForceOn is empty rather than guessing")
        void inForceLookupIsHonest() {
            assertThat(FeeRuleFixtures.fourRankResolver().ruleSetInForceOn(APRIL_2027.minusDays(1)))
                .isEmpty();
            assertThat(FeeRuleFixtures.fourRankResolver().ruleSetInForceOn(JUNE_2027))
                .isPresent();
        }
    }

    @Nested
    @DisplayName("the resolver's own wiring defects throw, because they are not data conditions")
    class WiringDefects {

        @Test
        @DisplayName("a resolver over no versions is refused")
        void emptyResolverIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolver(List.<FeeRuleSet>of()))
                .withMessageContaining("at least one rule set version");
        }

        @Test
        @DisplayName("one version id cannot name two readings")
        void duplicateVersionIdIsRefused() {
            // A stored rule_set_version_id has to identify a reading. Two sets under one id make
            // every posting classified by either indistinguishable on replay.
            FeeRuleSet first = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                    FeeClassification.INTEGRAL, "one reading")));
            FeeRuleSet second = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", OCTOBER_2027),
                List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, OCTOBER_2027,
                    FeeClassification.AS_INCURRED, "another reading, same id")));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolver(List.of(first, second)))
                .withMessageContaining("registered twice");
        }

        @Test
        @DisplayName("two versions cannot take effect on the same day")
        void sameEffectiveDateIsRefused() {
            // Nothing orders them, so which one classifies a posting would depend on the order the
            // register happened to hand them over — an arbitrary choice between two readings, which
            // is the same defect FR-202 forbids one level down.
            FeeRuleSet first = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                    FeeClassification.INTEGRAL, "one reading")));
            FeeRuleSet second = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.2", APRIL_2027),
                List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                    FeeClassification.AS_INCURRED, "another reading, same date")));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolver(List.of(first, second)))
                .withMessageContaining("both take effect on 2027-04-01");
        }

        @Test
        @DisplayName("two non-operative versions may share a date, because neither competes")
        void sameEffectiveDateIsAllowedWhileUnapproved() {
            // The date collision only exists between versions that actually compete in
            // ruleSetInForceOn. A bank revising a pending version holds two APPROVED sets on one
            // date, and neither classifies anything until its status advances — rejecting that would
            // defeat the reason a pending version can be held at all.
            FeeRuleSet firstDraft = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2028.1", LocalDate.of(2028, 4, 1),
                    PolicyVersionStatus.APPROVED),
                List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, LocalDate.of(2028, 4, 1),
                    FeeClassification.INTEGRAL, "pending reading")));
            FeeRuleSet revisedDraft = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2028.2", LocalDate.of(2028, 4, 1),
                    PolicyVersionStatus.APPROVED),
                List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, LocalDate.of(2028, 4, 1),
                    FeeClassification.AS_INCURRED, "revised pending reading")));

            FeeClassificationResolver resolver =
                new FeeClassificationResolver(List.of(firstDraft, revisedDraft));
            assertThat(resolver.ruleSetInForceOn(LocalDate.of(2028, 6, 30)))
                .as("neither is operative, so neither is in force and nothing is ambiguous")
                .isEmpty();
        }

        @Test
        @DisplayName("a refusal cannot cite a rule or a version")
        void refusalCitesNoEvidence() {
            // The mirror of the two evidence checks on the resolved branch, and it matters for the
            // same reason: a refusal carrying a rule reads as a refusal to the exception queue and
            // as "classified by an approved row" to anything rendering the trace.
            FeeRuleKey key = FeeRuleKey.catchAll(FeeRuleFixtures.PROC_FEE, JUNE_2027);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolution(
                    key, null,
                    FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                        FeeClassification.INTEGRAL, "r"),
                    null, ExceptionCategory.UNMAPPED_FEE_CODE, "a refusal citing a rule"))
                .withMessageContaining("A refusal has no evidence to cite");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolution(
                    key, null, null, "FEE-2027.1", ExceptionCategory.UNMAPPED_FEE_CODE,
                    "a refusal citing a version"))
                .withMessageContaining("A refusal has no evidence to cite");
        }

        @Test
        @DisplayName("a resolution cannot be both a classification and a refusal")
        void resolutionIsOneOrTheOther() {
            // A resolution carrying both is not a partly-good answer; it is one whose meaning
            // depends on which field the reader consults, and the two readers here are the
            // projector and the exception queue.
            FeeRuleKey key = FeeRuleKey.catchAll(FeeRuleFixtures.PROC_FEE, JUNE_2027);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolution(
                    key, FeeClassification.INTEGRAL, null, "FEE-2027.1",
                    ExceptionCategory.UNMAPPED_FEE_CODE, "both at once"))
                .withMessageContaining("not both or neither");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolution(
                    key, null, null, null, null, "neither"))
                .withMessageContaining("not both or neither");
        }

        @Test
        @DisplayName("a resolved classification must cite its rule and its version")
        void resolvedMustCiteEvidence() {
            FeeRuleKey key = FeeRuleKey.catchAll(FeeRuleFixtures.PROC_FEE, JUNE_2027);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolution(
                    key, FeeClassification.INTEGRAL, null, "FEE-2027.1", null, "no rule"))
                .withMessageContaining("names no rule");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolution(
                    key, FeeClassification.INTEGRAL,
                    FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                        FeeClassification.INTEGRAL, "r"),
                    "  ", null, "no version"))
                .withMessageContaining("names no rule set version");
        }
    }

    @Nested
    @DisplayName("replay pins the version the computation stored")
    class PinnedReplay {

        /** The 2027 reading, and a correction effective inside the same year. */
        private FeeClassificationResolver corrected() {
            return new FeeClassificationResolver(List.of(
                new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2027.1", LocalDate.of(2027, 1, 1),
                        PolicyVersionStatus.SUPERSEDED),
                    List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, LocalDate.of(2027, 1, 1),
                        FeeClassification.INTEGRAL, "the reading the period closed under"))),
                new FeeRuleSet(
                    FeeRuleFixtures.approved("FEE-2027.2", LocalDate.of(2027, 6, 1)),
                    List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, LocalDate.of(2027, 6, 1),
                        FeeClassification.AS_INCURRED, "a correction effective inside the year")))));
        }

        @Test
        @DisplayName("resolving by date gives today's reading; resolving by version id gives the stored one")
        void pinnedResolutionReproducesTheClosedPeriod() {
            // Date-based resolution answers "what governs a posting raised on this date", which is
            // right for a live run and wrong for a replay. A June 2027 computation closed under
            // FEE-2027.1, and the correction FEE-2027.2 took effect on 1 June — so resolving that
            // computation by date now returns AS_INCURRED, a figure nobody published. Invariant DT-1
            // requires the replay to reproduce what was published, which is why the computation
            // stores rule_set_version_id (04 § 2.6) and why the resolver can be pinned to it.
            FeeClassificationResolver resolver = corrected();
            FeeRuleKey lookup = FeeRuleKey.query(FeeRuleFixtures.PROC_FEE, null, null, JUNE_2027);

            assertThat(resolver.resolve(lookup).classification())
                .as("by date, the correction governs 30 June 2027")
                .isEqualTo(FeeClassification.AS_INCURRED);
            assertThat(resolver.resolveAgainstVersion(lookup, "FEE-2027.1").classification())
                .as("pinned to the version the computation stored, the original reading returns")
                .isEqualTo(FeeClassification.INTEGRAL);
            assertThat(resolver.resolveAgainstVersion(lookup, "FEE-2027.1").ruleSetVersionId())
                .isEqualTo("FEE-2027.1");
        }

        @Test
        @DisplayName("an unregistered version id is a hard failure, not a refusal")
        void unknownVersionIdThrows() {
            // A replay that cannot find the version it was closed under cannot be performed at all,
            // and quietly resolving against something else would be the DT-1 failure dressed as a
            // success. Not a data condition — the register was handed to the resolver by the caller.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> corrected().resolveAgainstVersion(
                    FeeRuleKey.query(FeeRuleFixtures.PROC_FEE, null, null, JUNE_2027), "FEE-1999.1"))
                .withMessageContaining("no fee rule set version FEE-1999.1 is registered")
                .withMessageContaining("FEE-2027.1");
        }

        @Test
        @DisplayName("an unapproved version cannot have classified anything")
        void unapprovedVersionCannotBePinned() {
            FeeRuleSet draft = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.9", APRIL_2027, PolicyVersionStatus.DRAFT),
                List.of(FeeRule.catchAll(FeeRuleFixtures.PROC_FEE, APRIL_2027,
                    FeeClassification.INTEGRAL, "never approved")));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeClassificationResolver(draft).resolveAgainstVersion(
                    FeeRuleKey.query(FeeRuleFixtures.PROC_FEE, null, null, JUNE_2027), "FEE-2027.9"))
                .withMessageContaining("cannot have classified anything");
        }

        @Test
        @DisplayName("pinning does not switch off rule-level dating")
        void pinnedResolutionStillRefusesOutsideRuleDates() {
            // Naming the version asserts which reading applies; it does not assert that every rule
            // in it was in force. A posting dated before the pinned version's own rows still refuses
            // — the rows' dates are part of the reading, not a separate gate over it.
            FeeClassificationResolution resolution = corrected().resolveAgainstVersion(
                FeeRuleKey.query(FeeRuleFixtures.PROC_FEE, null, null, LocalDate.of(2026, 12, 31)),
                "FEE-2027.1");
            assertThat(resolution.isResolved()).isFalse();
            assertThat(resolution.exception()).isEqualTo(ExceptionCategory.UNMAPPED_FEE_CODE);
        }
    }

    @Nested
    @DisplayName("the boundary this resolver feeds")
    class ProjectionBoundary {

        @Test
        @DisplayName("a resolved classification is what FeePosting consumes")
        void resolutionFeedsFeePosting() {
            // FeePosting takes an already-resolved classification and deliberately declines to
            // re-decide it, so this resolver is the only place the decision is made. Asserted here
            // because the two types are in different modules and nothing else ties them together.
            FeeClassificationResolution resolution = FeeRuleFixtures.fourRankResolver()
                .resolve(FeeRuleFixtures.PROC_FEE, FeeRuleFixtures.HOME_LOAN,
                    FeeRuleFixtures.NBFC, JUNE_2027);
            FeePosting posting = FeePosting.received(
                FeeRuleFixtures.PROC_FEE, Money.inr("15000.00"), JUNE_2027,
                resolution.requireClassification());

            // Rank 2 governs: the product matches, the entity does not. The fixture marks rank 2
            // INTEGRAL, so this posting enters the initial carrying amount.
            assertThat(posting.classification()).isEqualTo(FeeClassification.INTEGRAL);
            assertThat(posting.entersInitialCarryingAmount())
                .as("entersCarryingAmount() is true only for INTEGRAL")
                .isTrue();
            assertThat(resolution.entersCarryingAmount()).isTrue();
        }

        @Test
        @DisplayName("a penal charge resolves, and the ingestion boundary is what rejects it")
        void penalChargeResolvesThenIsFiltered() {
            // The division of labour of 03 § 3.4. The rule set says which codes are penal charges —
            // it must, or the filter has nothing to fire on — and FeePosting refuses to carry one
            // into any EIR stream (invariant PC-1). Legacy core banking systems route penal amounts
            // through the interest ledger, which is why this is a filter with a positive assertion
            // rather than an assumption.
            FeeRuleSet withPenal = new FeeRuleSet(
                FeeRuleFixtures.approved("FEE-2027.1", APRIL_2027),
                List.of(FeeRule.catchAll("PENAL_CHG", APRIL_2027,
                    FeeClassification.EXCLUDED_BY_DIRECTION,
                    "RBI 2023: a charge, not penal interest; not capitalised, bears no interest")));

            FeeClassificationResolution resolution = new FeeClassificationResolver(withPenal)
                .resolve("PENAL_CHG", FeeRuleFixtures.HOME_LOAN, FeeRuleFixtures.BANK, JUNE_2027);
            assertThat(resolution.classification())
                .as("the rule set resolves it — that is how the boundary knows")
                .isEqualTo(FeeClassification.EXCLUDED_BY_DIRECTION);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeePosting.received("PENAL_CHG", Money.inr("500.00"), JUNE_2027,
                    resolution.requireClassification()))
                .withMessageContaining("EXCLUDED_BY_DIRECTION");
        }
    }
}
