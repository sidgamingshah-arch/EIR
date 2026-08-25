package com.crisil.eir.policy.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Select-by-date across many versions of many kinds, and the sets the registry refuses.
 *
 * <p><b>Every expected answer here is read off a hand-built timeline written in the comments,
 * never off a run of the registry.</b> The timeline is three fee versions and one routing
 * version with dates chosen so that each boundary is a distinct calendar day, so the version
 * that governs any asserted date can be named by reading the fixture rather than by trusting
 * the code that selects it. A test whose expectation came from the selector would agree with a
 * selector that always returned the newest version, which is exactly the defect that matters.
 *
 * <p>The fee timeline, by hand (dates are the ACPIR go-live year):
 *
 * <pre>
 *   ... 2027-03-31   nothing on file — the engine had no fee rule set at all
 *   2027-04-01 ..    FEE-2027.0   go-live baseline            (SUPERSEDED)
 *   2027-07-01 ..    FEE-2027.1   selling-agent split, ACPIR 53 (SUPERSEDED)
 *   2027-10-01 ..    FEE-2027.2   commitment-fee threshold    (EFFECTIVE)
 * </pre>
 *
 * <p>And, independently, on its own clock:
 *
 * <pre>
 *   2026-11-01 ..    RT-2026.1    routing baseline            (EFFECTIVE)
 * </pre>
 *
 * <p>The ranges above are read off the <em>timeline</em>, not off any end-date field: no version
 * carries one, and the successor's start date is the predecessor's end date. Which is why
 * {@link SelectByDate#bothVersionsClaimTheDateAndOnlyOneGoverns()} is the test that pins the
 * whole thing — every operative version claims a late date when asked on its own.
 */
class PolicyVersionRegistryTest {

    // ---- The hand-built timeline. Boundaries one calendar day apart, so every assertion below
    // ---- names a date that falls unambiguously inside exactly one version's range.
    private static final LocalDate FEE_0_FROM = LocalDate.of(2027, 4, 1);
    private static final LocalDate FEE_1_FROM = LocalDate.of(2027, 7, 1);
    private static final LocalDate FEE_2_FROM = LocalDate.of(2027, 10, 1);
    private static final LocalDate ROUTING_FROM = LocalDate.of(2026, 11, 1);

    private static PolicyVersion version(
        String id, PolicyKind kind, LocalDate effectiveFrom, PolicyVersionStatus status) {
        // Approved a fortnight before it takes effect: forward-dated, so isRetrospective() is
        // false and nothing in these tests turns on the retrospective flag.
        return new PolicyVersion(
            id, kind, "fixture version " + id, effectiveFrom,
            "policy.author", "accounting.policy.owner", effectiveFrom.minusDays(14), status);
    }

    private static PolicyVersion fee0() {
        return version("FEE-2027.0", PolicyKind.FEE_RULE_SET, FEE_0_FROM,
            PolicyVersionStatus.SUPERSEDED);
    }

    private static PolicyVersion fee1() {
        return version("FEE-2027.1", PolicyKind.FEE_RULE_SET, FEE_1_FROM,
            PolicyVersionStatus.SUPERSEDED);
    }

    private static PolicyVersion fee2() {
        return version("FEE-2027.2", PolicyKind.FEE_RULE_SET, FEE_2_FROM,
            PolicyVersionStatus.EFFECTIVE);
    }

    private static PolicyVersion routing() {
        return version("RT-2026.1", PolicyKind.ROUTING_TABLE, ROUTING_FROM,
            PolicyVersionStatus.EFFECTIVE);
    }

    /** The three fee versions, deliberately supplied out of date order. */
    private static PolicyVersionRegistry feeTimeline() {
        // Newest first. Insertion order must not decide anything: a registry that answered by
        // insertion order would return FEE-2027.2 for every date, and every date-selection
        // assertion below would still pass if the selector merely returned the FIRST entry.
        return PolicyVersionRegistry.of(fee2(), fee0(), fee1());
    }

    @Nested
    @DisplayName("select by date: the version in force is the latest one that has started")
    class SelectByDate {

        @Test
        @DisplayName("each date resolves to the version whose range contains it")
        void eachDateResolvesToItsOwnVersion() {
            PolicyVersionRegistry registry = feeTimeline();

            // Read off the timeline in the class comment, not off the registry. 30 June 2027 is
            // the last day of FEE-2027.0's range because FEE-2027.1 starts on 1 July; 30
            // September is the last day of FEE-2027.1's for the same reason.
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 4, 1)))
                .as("the day the baseline takes effect — the boundary is inclusive")
                .contains(fee0());
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 6, 30)))
                .as("the day before the second version starts")
                .contains(fee0());
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 7, 1)))
                .as("supersession is an end-date: the successor takes over on its own first day")
                .contains(fee1());
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 9, 30)))
                .contains(fee1());
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 10, 1)))
                .contains(fee2());
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2031, 1, 1)))
                .as("the newest version is open-ended; nothing has replaced it")
                .contains(fee2());
        }

        @Test
        @DisplayName("insertion order decides nothing")
        void insertionOrderDecidesNothing() {
            // The same three versions in all six orders must give the same answer for a date
            // inside the middle range. This is the check that catches a selector reading the
            // first or last entry, or a map iteration order leaking into an accounting answer.
            LocalDate insideMiddleRange = LocalDate.of(2027, 8, 15);
            List<List<PolicyVersion>> permutations = List.of(
                List.of(fee0(), fee1(), fee2()),
                List.of(fee0(), fee2(), fee1()),
                List.of(fee1(), fee0(), fee2()),
                List.of(fee1(), fee2(), fee0()),
                List.of(fee2(), fee0(), fee1()),
                List.of(fee2(), fee1(), fee0()));
            for (List<PolicyVersion> order : permutations) {
                assertThat(PolicyVersionRegistry.of(order)
                    .inForceOn(PolicyKind.FEE_RULE_SET, insideMiddleRange))
                    .as("15 August 2027 lies in FEE-2027.1's range whatever order the rows"
                        + " arrive in; supplied as %s", order.stream().map(PolicyVersion::id)
                        .toList())
                    .contains(fee1());
            }
        }

        @Test
        @DisplayName("every operative version claims a late date; the registry still names one")
        void bothVersionsClaimTheDateAndOnlyOneGoverns() {
            // The test that pins latest-wins, and the reason it is a decision rather than an
            // implementation detail. PolicyVersion.isEffectiveOn is open-ended —
            // status.isOperative() && !date.isBefore(effectiveFrom) — and SUPERSEDED is
            // operative so closed periods still replay (DT-1). So on 31 December 2027 ALL THREE
            // fee versions answer true when asked one at a time: 1 April, 1 July and 1 October
            // have all passed and none carries an end date. Reading a version in isolation
            // therefore cannot answer the question, and there is no end-date column to consult.
            // The successor's start date is the predecessor's end date, so the greatest
            // effective date not after the query wins — here 1 October 2027, FEE-2027.2.
            LocalDate late = LocalDate.of(2027, 12, 31);
            assertThat(fee0().isEffectiveOn(late)).isTrue();
            assertThat(fee1().isEffectiveOn(late)).isTrue();
            assertThat(fee2().isEffectiveOn(late)).isTrue();

            assertThat(feeTimeline().inForceOn(PolicyKind.FEE_RULE_SET, late))
                .as("three versions claim 31 December 2027; exactly one governs it")
                .contains(fee2());
            assertThat(feeTimeline().historyOf(PolicyKind.FEE_RULE_SET))
                .as("and the other two are still on file, resolvable for their own periods")
                .containsExactly(fee0(), fee1(), fee2());
        }

        @Test
        @DisplayName("a date before any version resolves to nothing, not to the earliest")
        void beforeEverythingResolvesToNothing() {
            // 31 March 2027 is the day before the go-live baseline. Answering it with
            // FEE-2027.0 would apply a rule to a period that closed before the rule was
            // written — the single most dangerous convenience a registry could offer, because
            // the answer looks entirely plausible in a report.
            assertThat(feeTimeline().inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 3, 31)))
                .as("no fee rule set existed on 31 March 2027")
                .isEmpty();

            assertThatIllegalStateException()
                .isThrownBy(() -> feeTimeline()
                    .requireInForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 3, 31)))
                .withMessageContaining("not to the earliest version")
                .withMessageContaining("FEE-2027.0 from 2027-04-01");
        }

        @Test
        @DisplayName("an APPROVED version is not yet resolvable, however old its date")
        void approvedButNotEffectiveIsNotSelected() {
            // FEE-2027.9 is signed off and dated 1 January 2028, but has not been advanced to
            // EFFECTIVE. On 1 February 2028 the latest STARTED version is FEE-2027.9 and the
            // latest OPERATIVE one is FEE-2027.2 — so a selector that filtered on date alone
            // and forgot status would return the wrong one here, and only here.
            PolicyVersion notYetLive = version(
                "FEE-2027.9", PolicyKind.FEE_RULE_SET, LocalDate.of(2028, 1, 1),
                PolicyVersionStatus.APPROVED);
            PolicyVersionRegistry registry =
                PolicyVersionRegistry.of(fee0(), fee1(), fee2(), notYetLive);

            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2028, 2, 1)))
                .as("approved is not in force; the previous version still governs")
                .contains(fee2());
            assertThat(registry.historyOf(PolicyKind.FEE_RULE_SET))
                .as("the history is the operative versions; an approved one is not in it yet")
                .containsExactly(fee0(), fee1(), fee2());
        }

        @Test
        @DisplayName("a DRAFT is invisible to date selection")
        void draftIsNeverSelected() {
            PolicyVersion draft = new PolicyVersion(
                "FEE-2028.0-draft", PolicyKind.FEE_RULE_SET, "in progress",
                LocalDate.of(2027, 12, 1), "policy.author", null, null,
                PolicyVersionStatus.DRAFT);
            PolicyVersionRegistry registry = PolicyVersionRegistry.of(fee2(), draft);

            // 1 December 2027 is the draft's own effective date, and the draft still loses to
            // FEE-2027.2 — nobody has approved it, so it governs nothing.
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 12, 1)))
                .contains(fee2());
            assertThat(registry.findById("FEE-2028.0-draft"))
                .as("held and addressable, just not resolvable by date")
                .contains(draft);
        }

        @Test
        @DisplayName("an empty registry answers nothing rather than guessing")
        void emptyRegistry() {
            PolicyVersionRegistry empty = PolicyVersionRegistry.of();
            assertThat(empty.size()).isZero();
            assertThat(empty.inForceOn(PolicyKind.FEE_RULE_SET, FEE_0_FROM)).isEmpty();
            assertThat(empty.inForceOn(FEE_0_FROM)).isEmpty();
            assertThat(empty.resolvableKinds()).isEmpty();
            assertThat(empty.describeInForceOn(FEE_0_FROM))
                .isEqualTo("no policy version in force on 2027-04-01");
        }
    }

    @Nested
    @DisplayName("overlapping effective ranges are refused at construction")
    class OverlapRefusal {

        @Test
        @DisplayName("two EFFECTIVE versions of one kind on one date are refused")
        void twoEffectiveOnOneDate() {
            // The core refusal. Both would answer 1 October 2027, and no rule over dates
            // separates them: whichever the iteration reached first would govern published
            // income. Refused when the set is built — before any figure depends on it.
            PolicyVersion rival = version(
                "FEE-2027.2-alt", PolicyKind.FEE_RULE_SET, FEE_2_FROM,
                PolicyVersionStatus.EFFECTIVE);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> PolicyVersionRegistry.of(fee0(), fee1(), fee2(), rival))
                .withMessageContaining("two approved FEE_RULE_SET versions both take effect on"
                    + " 2027-10-01")
                .withMessageContaining("'FEE-2027.2' and 'FEE-2027.2-alt'")
                .withMessageContaining("not an accounting answer");
        }

        @Test
        @DisplayName("a SUPERSEDED version clashing with its replacement is refused too")
        void supersededClashIsRefusedToo() {
            // The backdated-restatement shape: FEE-2027.1r replaces FEE-2027.1 from the same
            // 1 July 2027. Both are operative — SUPERSEDED stays resolvable so closed periods
            // replay — so 1 July has two answers. Refused, and the message names the only two
            // honest fixes: a distinct effective date, or resolution by stored version id.
            PolicyVersion restatement = version(
                "FEE-2027.1r", PolicyKind.FEE_RULE_SET, FEE_1_FROM,
                PolicyVersionStatus.EFFECTIVE);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> PolicyVersionRegistry.of(fee0(), fee1(), restatement))
                .withMessageContaining("2027-07-01")
                .withMessageContaining("resolve the restatement by version id");
        }

        @Test
        @DisplayName("an APPROVED version clashing with an EFFECTIVE one is refused now, not later")
        void approvedClashIsRefusedEarly() {
            // FEE-2027.2 is in force from 1 October 2027; FEE-2027.3 is signed off for the same
            // day and merely not advanced yet. Admitting the pair would build a registry that
            // works until somebody flips a status, then becomes unanswerable mid-period. The
            // clash exists the moment the checker signs, so that is when it is refused.
            PolicyVersion signedForTheSameDay = version(
                "FEE-2027.3", PolicyKind.FEE_RULE_SET, FEE_2_FROM, PolicyVersionStatus.APPROVED);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> PolicyVersionRegistry.of(fee2(), signedForTheSameDay))
                .withMessageContaining("'FEE-2027.2' and 'FEE-2027.3'");
        }

        @Test
        @DisplayName("two DRAFTs may share a date: competing candidates, resolvable by nothing")
        void draftsMayShareADate() {
            // Two makers drafting alternatives for 1 December 2027 is the normal shape of a
            // policy change in progress. Neither is resolvable by date, so neither can create
            // the ambiguity the refusal exists to prevent; refusing them would make the
            // registry unusable as a working store.
            PolicyVersion candidateA = new PolicyVersion(
                "FEE-2028.a", PolicyKind.FEE_RULE_SET, "candidate A",
                LocalDate.of(2027, 12, 1), "maker.a", null, null, PolicyVersionStatus.DRAFT);
            PolicyVersion candidateB = new PolicyVersion(
                "FEE-2028.b", PolicyKind.FEE_RULE_SET, "candidate B",
                LocalDate.of(2027, 12, 1), "maker.b", null, null,
                PolicyVersionStatus.PENDING_APPROVAL);

            PolicyVersionRegistry registry = PolicyVersionRegistry.of(fee2(), candidateA, candidateB);
            assertThat(registry.size()).isEqualTo(3);
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 12, 1)))
                .as("both drafts govern nothing, so FEE-2027.2 still governs")
                .contains(fee2());
        }

        @Test
        @DisplayName("one id naming two different versions is refused")
        void oneIdTwoVersions() {
            // A computation cites a version id (04 § 2.12) and a replay resolves that id. An id
            // naming two things makes the closed period's own record ambiguous, which is the
            // same non-answer as picking by iteration order — so it is refused even when the
            // two rows carry different kinds and could never clash on a date.
            PolicyVersion sameIdOtherKind = version(
                "FEE-2027.2", PolicyKind.ROUTING_TABLE, ROUTING_FROM,
                PolicyVersionStatus.EFFECTIVE);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> PolicyVersionRegistry.of(fee2(), sameIdOtherKind))
                .withMessageContaining("names two different versions")
                .withMessageContaining("unreplayable");
        }

        @Test
        @DisplayName("the same version supplied twice is a duplicated row, not a conflict")
        void identicalDuplicateIsFolded() {
            // Two identical rows change no answer the registry can give — a policy table loaded
            // from two overlapping sources is a housekeeping matter, not an accounting one.
            PolicyVersionRegistry registry = PolicyVersionRegistry.of(fee2(), fee2());
            assertThat(registry.size()).as("folded to one").isEqualTo(1);
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, FEE_2_FROM)).contains(fee2());
        }

        @Test
        @DisplayName("versions of different kinds share dates freely")
        void differentKindsDoNotClash() {
            // A fee repricing and a routing amendment landing on the same day is a coincidence,
            // not an ambiguity: they answer different questions. If this ever threw, ADR-0006's
            // premise — that the routing table is independently versionable — would be false.
            PolicyVersion routingOnFeeDate = version(
                "RT-2027.2", PolicyKind.ROUTING_TABLE, FEE_2_FROM, PolicyVersionStatus.EFFECTIVE);
            PolicyVersion tierOnFeeDate = version(
                "TIER-2027.1", PolicyKind.TIER_ASSIGNMENT, FEE_2_FROM,
                PolicyVersionStatus.EFFECTIVE);

            PolicyVersionRegistry registry =
                PolicyVersionRegistry.of(fee2(), routingOnFeeDate, tierOnFeeDate);
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, FEE_2_FROM)).contains(fee2());
            assertThat(registry.inForceOn(PolicyKind.ROUTING_TABLE, FEE_2_FROM))
                .contains(routingOnFeeDate);
            assertThat(registry.inForceOn(PolicyKind.TIER_ASSIGNMENT, FEE_2_FROM))
                .contains(tierOnFeeDate);
        }
    }

    @Nested
    @DisplayName("a timeline whose statuses contradict its dates is reported, not refused")
    class SupersessionCoherence {

        @Test
        @DisplayName("a coherent timeline passes")
        void coherentTimelinePasses() {
            // FEE-2027.0 and .1 SUPERSEDED, .2 EFFECTIVE: each replacement recorded, the newest
            // one in force. Nothing to report.
            InvariantResult result = feeTimeline().supersessionCoherentFor(PolicyKind.FEE_RULE_SET);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.detail()).contains("newest EFFECTIVE");
        }

        @Test
        @DisplayName("a replaced version left EFFECTIVE is reported, and still resolves correctly")
        void replacedVersionLeftEffective() {
            // FEE-2027.1 EFFECTIVE from 1 July 2027 and FEE-2027.2 EFFECTIVE from 1 October.
            // Latest-wins still answers FEE-2027.2 for 1 November — so this is not refused at
            // construction, because the date IS answerable. What is wrong is the status record:
            // a reader taking isEffectiveOn or status() == EFFECTIVE at face value sees two
            // versions in force on one date. Reported so somebody fixes the record.
            PolicyVersion neverRetired = version(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, FEE_1_FROM, PolicyVersionStatus.EFFECTIVE);
            PolicyVersionRegistry registry = PolicyVersionRegistry.of(neverRetired, fee2());

            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 11, 1)))
                .as("resolution is unaffected: the later effective date wins")
                .contains(fee2());

            InvariantResult result = registry.supersessionCoherentFor(PolicyKind.FEE_RULE_SET);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("one version implicated, so the deviation is a count of one")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail())
                .contains("'FEE-2027.1' effective 2027-07-01 is still EFFECTIVE")
                .contains("'FEE-2027.2' takes effect on 2027-10-01");
        }

        @Test
        @DisplayName("a SUPERSEDED newest version is reported: the successor was never loaded")
        void supersededWithNoSuccessor() {
            // The partial-load defect, and the reason this check exists. FEE-2027.0 and .1 are
            // both SUPERSEDED, so whatever replaced FEE-2027.1 — FEE-2027.2, from 1 October
            // 2027 — is missing from the load. Latest-wins then answers 1 November 2027 with
            // FEE-2027.1, a rule set the bank retired a month earlier, and policyResolvableOn
            // reports satisfied throughout because a version does resolve. It is simply the
            // wrong one, and only this check says so.
            PolicyVersionRegistry historyOnly = PolicyVersionRegistry.of(fee0(), fee1());
            assertThat(historyOnly.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 11, 1)))
                .as("latest-wins answers with the retired version — resolvable, and wrong")
                .contains(fee1());
            assertThat(historyOnly
                .policyResolvableOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 11, 1))
                .satisfied())
                .as("resolvability cannot see this: a version did resolve")
                .isTrue();

            InvariantResult result =
                historyOnly.supersessionCoherentFor(PolicyKind.FEE_RULE_SET);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail())
                .contains("'FEE-2027.1' effective 2027-07-01 is the newest operative version and"
                    + " is SUPERSEDED")
                .contains("no later version of this kind is held at all")
                .contains("Load the successor");
        }

        @Test
        @DisplayName("the report names a successor that exists but is not yet operative")
        void supersededWithAnApprovedSuccessor() {
            // The half-done status advance: FEE-2027.1 was retired when its replacement was
            // APPROVED rather than when the replacement took effect, leaving the gap in the
            // middle of the timeline rather than at its end. Same breach, different diagnosis,
            // and the detail has to say which — guessing between "row never loaded" and
            // "status advance half-done" is the slow part of the investigation.
            PolicyVersion approvedSuccessor = version(
                "FEE-2027.2", PolicyKind.FEE_RULE_SET, FEE_2_FROM, PolicyVersionStatus.APPROVED);
            InvariantResult result = PolicyVersionRegistry.of(fee0(), fee1(), approvedSuccessor)
                .supersessionCoherentFor(PolicyKind.FEE_RULE_SET);

            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail())
                .contains("'FEE-2027.2' effective 2027-10-01 is APPROVED, so it governs nothing"
                    + " yet");
        }

        @Test
        @DisplayName("a kind with no operative version is not incoherent, merely absent")
        void noTimelineIsNotAnIncoherentTimeline() {
            // The two questions are distinct: whether a kind ought to be on file is
            // policyResolvableOn's, asked of a date. This one asks only whether the versions
            // held contradict each other, and none held cannot.
            InvariantResult result =
                feeTimeline().supersessionCoherentFor(PolicyKind.POOL_DEFINITION);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("no operative POOL_DEFINITION version held");
        }

        @Test
        @DisplayName("a single EFFECTIVE version is a complete timeline")
        void oneEffectiveVersionIsFine() {
            // The state on the day after go-live, and the state of the routing table for all of
            // Phase 2: one version, never replaced. Nothing to be incoherent about.
            PolicyVersion fee0AsEffective = version(
                "FEE-2027.0", PolicyKind.FEE_RULE_SET, FEE_0_FROM, PolicyVersionStatus.EFFECTIVE);
            PolicyVersionRegistry registry = PolicyVersionRegistry.of(fee0AsEffective);
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 5, 31)))
                .contains(fee0AsEffective);
            assertThat(registry.supersessionCoherentFor(PolicyKind.FEE_RULE_SET).satisfied())
                .isTrue();
        }
    }

    @Nested
    @DisplayName("history stays resolvable, because a closed period must replay (DT-1)")
    class ClosedPeriodsStillResolve {

        @Test
        @DisplayName("a SUPERSEDED version still governs the period it governed")
        void supersededStillGovernsItsOwnPeriod() {
            // Invariant DT-1: a re-run of a closed period must reproduce the published figures
            // bit-identically (03 § 9, control C-12). The Q1 FY28 close of 30 June 2027 ran
            // under FEE-2027.0, which two supersessions later is still the answer for that
            // date. If supersession deleted history this assertion would fail and every replay
            // of that quarter would silently use today's fee policy.
            PolicyVersionRegistry registry = feeTimeline();
            Optional<PolicyVersion> governedTheClose =
                registry.inForceOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 6, 30));
            assertThat(governedTheClose).contains(fee0());
            assertThat(governedTheClose.orElseThrow().status())
                .as("resolvable precisely because SUPERSEDED is operative")
                .isEqualTo(PolicyVersionStatus.SUPERSEDED);
        }

        @Test
        @DisplayName("the invariant passes for a date on file and fails for one before it")
        void resolvabilityIsAssertedPositively() {
            PolicyVersionRegistry registry = feeTimeline();

            InvariantResult inside =
                registry.policyResolvableOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 6, 30));
            assertThat(inside.id()).isEqualTo(InvariantId.DT_1);
            assertThat(inside.satisfied()).isTrue();
            assertThat(inside.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(inside.detail())
                .as("the detail names the version, so a workpaper can cite it")
                .contains("FEE-2027.0")
                .contains("2027-06-30");

            // 31 March 2027, the day before the baseline: a period nothing on file can replay.
            InvariantResult before =
                registry.policyResolvableOn(PolicyKind.FEE_RULE_SET, LocalDate.of(2027, 3, 31));
            assertThat(before.satisfied()).isFalse();
            assertThat(before.deviation())
                .as("the breach is an absent answer, which has no money size; one per"
                    + " unresolvable date, so summing deviations counts them")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(before.detail()).contains("cannot be replayed");
        }

        @Test
        @DisplayName("the invariant fails for a kind the registry never held")
        void unheldKindIsABreachNotAnEmptyPass() {
            // A registry with no behavioural curve at all must not report a curve-dependent
            // period as resolvable. The failure names the gap rather than the date.
            InvariantResult result = feeTimeline()
                .policyResolvableOn(PolicyKind.BEHAVIOURAL_CURVE, LocalDate.of(2027, 6, 30));
            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail()).contains("no operative BEHAVIOURAL_CURVE version at all");
        }

        @Test
        @DisplayName("a replay resolves the stored id, including a superseded one")
        void replayResolvesById() {
            // What a replay actually does: it reads the version id stored on the event rather
            // than re-selecting by date (RoutingTableVersion says the same of routing). So
            // every version stays addressable by id, operative or not.
            PolicyVersionRegistry registry = feeTimeline();
            assertThat(registry.findById("FEE-2027.0")).contains(fee0());
            assertThat(registry.findById("FEE-2027.2")).contains(fee2());
            assertThat(registry.findById("FEE-2099.9"))
                .as("an unknown id is empty, not a substituted neighbour")
                .isEmpty();
        }

        @Test
        @DisplayName("the history of a kind reads oldest first, with nothing missing")
        void historyIsOrderedAndComplete() {
            // The sequence a reviewer reads to confirm that every date since 1 April 2027 has
            // exactly one governing reading. Ordered by effective date regardless of the order
            // the rows were supplied (the fixture supplies them newest first).
            assertThat(feeTimeline().historyOf(PolicyKind.FEE_RULE_SET))
                .containsExactly(fee0(), fee1(), fee2());
            assertThat(feeTimeline().historyOf(PolicyKind.POOL_DEFINITION))
                .as("a kind with nothing on file has an empty history, not a null one")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("the kinds are independent, and a run snapshots all of them at once")
    class KindsAreIndependent {

        private PolicyVersionRegistry mixed() {
            return PolicyVersionRegistry.of(fee0(), fee1(), fee2(), routing());
        }

        @Test
        @DisplayName("one kind's timeline does not shift another's")
        void oneKindDoesNotShiftAnother() {
            // Routing has been in force since 1 November 2026 and fees only since 1 April 2027.
            // On 31 March 2027 routing resolves and fees do not — the case that fails
            // immediately if the registry pools the kinds into one list and takes the latest
            // started version overall.
            PolicyVersionRegistry registry = mixed();
            LocalDate beforeFeesExisted = LocalDate.of(2027, 3, 31);
            assertThat(registry.inForceOn(PolicyKind.ROUTING_TABLE, beforeFeesExisted))
                .contains(routing());
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, beforeFeesExisted)).isEmpty();

            // And after two fee supersessions, routing is still on its own single version.
            assertThat(registry.inForceOn(PolicyKind.ROUTING_TABLE, LocalDate.of(2027, 10, 1)))
                .as("a fee repricing does not advance the routing table (ADR-0006)")
                .contains(routing());
        }

        @Test
        @DisplayName("the snapshot holds one version per resolvable kind and omits the rest")
        void snapshotOmitsUnresolvableKinds() {
            // What a run stamps on its output (04 § 2.13, FR-903). On 15 August 2027 the answer
            // is, by the timeline: fees FEE-2027.1, routing RT-2026.1, and nothing else on file.
            Map<PolicyKind, PolicyVersion> snapshot = mixed().inForceOn(LocalDate.of(2027, 8, 15));
            assertThat(snapshot)
                .containsOnlyKeys(PolicyKind.FEE_RULE_SET, PolicyKind.ROUTING_TABLE)
                .containsEntry(PolicyKind.FEE_RULE_SET, fee1())
                .containsEntry(PolicyKind.ROUTING_TABLE, routing());

            // Before the fee rule set existed, the snapshot is routing alone — an absent key,
            // never a null value, so a caller cannot iterate past a missing policy.
            assertThat(mixed().inForceOn(LocalDate.of(2026, 12, 31)))
                .containsOnlyKeys(PolicyKind.ROUTING_TABLE);
            assertThat(mixed().inForceOn(LocalDate.of(2026, 10, 31)))
                .as("before every version of every kind")
                .isEmpty();
        }

        @Test
        @DisplayName("the audit sentence names the versions in force")
        void auditSentence() {
            String sentence = mixed().describeInForceOn(LocalDate.of(2027, 8, 15));
            assertThat(sentence)
                .contains("in force on 2027-08-15")
                .contains("FEE_RULE_SET version FEE-2027.1")
                .contains("ROUTING_TABLE version RT-2026.1")
                .doesNotContain("FEE-2027.2");
        }

        @Test
        @DisplayName("resolvableKinds names only kinds with an operative version")
        void resolvableKindsIsOperativeOnly() {
            PolicyVersion draftCurve = new PolicyVersion(
                "CURVE-2028.0", PolicyKind.BEHAVIOURAL_CURVE, "prepayment speeds, in progress",
                LocalDate.of(2027, 1, 1), "quant", null, null, PolicyVersionStatus.DRAFT);
            PolicyVersionRegistry registry =
                PolicyVersionRegistry.of(fee2(), routing(), draftCurve);
            assertThat(registry.resolvableKinds())
                .as("a drafted curve makes no date resolvable")
                .containsExactlyInAnyOrder(PolicyKind.FEE_RULE_SET, PolicyKind.ROUTING_TABLE);
            assertThat(registry.size()).as("but it is still held").isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("immutable, and loud about nulls")
    class ImmutabilityAndNulls {

        @Test
        @DisplayName("mutating the source collection afterwards cannot change an answer")
        void sourceCollectionIsCopied() {
            // A registry that changed after a run resolved against it would make the run's own
            // audit trail unreproducible — the DT-1 failure that leaves no evidence.
            // The two-version state before the third version was approved: FEE-2027.1 was the
            // one in force, so it is EFFECTIVE here rather than SUPERSEDED.
            PolicyVersion feeOneWhileInForce = version(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, FEE_1_FROM, PolicyVersionStatus.EFFECTIVE);
            List<PolicyVersion> source = new ArrayList<>(List.of(fee0(), feeOneWhileInForce));
            PolicyVersionRegistry registry = PolicyVersionRegistry.of(source);
            source.add(fee2());
            source.clear();

            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.inForceOn(PolicyKind.FEE_RULE_SET, FEE_2_FROM))
                .as("FEE-2027.2 was added to the list after construction and is not in force")
                .contains(feeOneWhileInForce);
        }

        @Test
        @DisplayName("the views handed out cannot be edited")
        void viewsAreUnmodifiable() {
            PolicyVersionRegistry registry = feeTimeline();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> registry.historyOf(PolicyKind.FEE_RULE_SET).add(routing()));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> registry.versions().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> registry.inForceOn(FEE_2_FROM)
                    .put(PolicyKind.ROUTING_TABLE, routing()));
        }

        @Test
        @DisplayName("nulls are refused rather than treated as absent")
        void nullsAreRefused() {
            PolicyVersionRegistry registry = feeTimeline();
            assertThatNullPointerException()
                .isThrownBy(() -> registry.inForceOn(null, FEE_2_FROM));
            assertThatNullPointerException()
                .isThrownBy(() -> registry.inForceOn(PolicyKind.FEE_RULE_SET, null));
            assertThatNullPointerException().isThrownBy(() -> registry.findById(null));
            assertThatNullPointerException()
                .isThrownBy(() -> PolicyVersionRegistry.of(Arrays.asList(fee0(), null)));
        }
    }
}
