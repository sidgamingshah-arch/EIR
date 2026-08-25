package com.crisil.eir.policy.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-210's gate. <b>The refusal is the deliverable</b>, so this file is mostly refusals, and the
 * ones that matter most are the six that fire on a stored preview which would satisfy any control
 * asking merely whether a preview exists.
 *
 * <p>The version under test is the change the roadmap's risk register names: a behavioural-curve
 * revision compressing assumed life from 240 months to 96. 03 § 3.6 prices it — year-one net fee
 * recognition of 2,525.04 becomes 9,410.03 on one exposure, <b>3.73x</b> — and 03 § 6.3 explains
 * why it arrives in a single period: a CPR revision is a B5.4.6 catch-up across every affected
 * contract simultaneously. Every money figure below is that per-contract movement,
 * 9,410.03 - 2,525.04 = 6,884.99, times a thousand identical contracts = 6,884,990.00.
 *
 * <p>The dates are chosen so each boundary can be checked by hand and are restated at each
 * assertion:
 *
 * <pre>
 *   approved            2027-03-15   (a date, no zone — see the anywhere-on-Earth bound below)
 *   effective from      2027-04-01
 *   activation attempt  2027-04-01T02:00:00Z
 *   preview generated   2027-03-10T09:30:00Z  — 21 whole days before the attempt
 * </pre>
 */
class ActivationGateTest {

    private static final String VERSION_ID = "CURVE-2027.1";
    private static final DraftFingerprint EIGHT_YEAR_DRAFT =
        DraftFingerprint.of("EXPECTED_LIFE_MONTHS=96", "CPR=12");
    private static final DraftFingerprint TWENTY_YEAR_DRAFT =
        DraftFingerprint.of("EXPECTED_LIFE_MONTHS=240", "CPR=12");

    private static final LocalDate APPROVED_ON = LocalDate.of(2027, 3, 15);
    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2027, 4, 1);
    private static final LocalDate BOOK_AS_OF = LocalDate.of(2027, 2, 28);
    private static final Instant ACTIVATION = Instant.parse("2027-04-01T02:00:00Z");
    private static final Instant GENERATED = Instant.parse("2027-03-10T09:30:00Z");

    /**
     * The last instant the calendar date 2027-03-15 can still be current anywhere on Earth:
     * midnight starting 16 March at UTC-12 is 2027-03-16T12:00:00Z. Derived by hand, and the
     * bound the approval-order check uses so that a genuine same-day preview is never refused
     * for want of a time zone the record does not carry.
     */
    private static final Instant END_OF_APPROVAL_DAY_ANYWHERE =
        Instant.parse("2027-03-16T12:00:00Z");

    private static final Rate EIR_BEFORE = Rate.annualEffective(new BigDecimal("0.09533867"));
    private static final Rate EIR_AFTER = Rate.annualEffective(new BigDecimal("0.09689903"));
    private static final Money PER_CONTRACT = Money.inr("6884.99");
    private static final Money PORTFOLIO = Money.inr("6884990.00");

    private static final ActivationGate GATE = ActivationGate.withDefaultHorizon();

    private static PolicyVersion curveVersion(PolicyVersionStatus status) {
        return new PolicyVersion(
            VERSION_ID, PolicyKind.BEHAVIOURAL_CURVE,
            "CPR revision compressing assumed life from 240 to 96 months",
            EFFECTIVE_FROM, "curve.owner", "accounting.policy.owner", APPROVED_ON, status);
    }

    private static PolicyVersion approvedCurveVersion() {
        return curveVersion(PolicyVersionStatus.APPROVED);
    }

    /**
     * A preview with a chosen version id, draft, timestamp and coherence.
     *
     * <p>The incoherent variant reports zero contracts affected alongside a 6,884,990.00
     * portfolio movement — the aggregation defect of counting one population and summing
     * another.
     *
     * <p>The book position is derived from the generation instant — the previous day — rather
     * than fixed, for two reasons. A preview cannot have measured a book that had not begun when
     * it ran, so a fixed date would make an early-dated fixture incoherent for a reason the test
     * is not about. And the staleness horizon is measured from the older of the two anchors, so a
     * fixed book date would silently dominate the age of every fixture. Previous-day at these
     * times of day keeps the generation instant the older anchor, which isolates one variable at
     * a time.
     */
    private static ImpactPreview previewOf(
        String versionId, DraftFingerprint draft, Instant generatedAt, boolean coherent) {
        LocalDate bookAsOf = generatedAt.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        return new ImpactPreview(
            versionId, draft, generatedAt, bookAsOf, coherent ? 1_000L : 0L,
            PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
    }

    private static ImpactPreview goodPreview() {
        return previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, GENERATED, true);
    }

    @Nested
    @DisplayName("a usable preview lets the version through, and says which one")
    class Permitted {

        @Test
        @DisplayName("a coherent, current, timely preview permits activation")
        void permits() {
            ActivationDecision decision =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, goodPreview(), ACTIVATION);

            assertThat(decision.permitted()).isTrue();
            assertThat(decision.isRefused()).isFalse();
            assertThat(decision.refusalReason()).isEmpty();
            assertThat(decision.refusalLooksLikeDiligence()).isFalse();
            assertThat(decision.policyVersionId()).isEqualTo(VERSION_ID);
            // The permit quotes the preview it relied on. A gate that answers only "yes" leaves
            // nothing in the record to show WHICH preview was relied on, and that is the field
            // an auditor asks for first.
            assertThat(decision.detail())
                .contains("impact preview of CURVE-2027.1")
                .contains("1000 contracts affected")
                .contains("INR 6884990.00")
                .contains("21 days stale against a horizon of 90 days");
            assertThat(decision.describe()).startsWith("ACTIVATION PERMITTED for policy version");
        }

        @Test
        @DisplayName("the register entry point gives the same answer as the direct one")
        void registerEntryPointAgrees() {
            // The two entry points exist for two callers — one holding a resolved
            // impact_preview_ref (04 § 2.12), one holding the version's whole preview history —
            // and a gate that answered differently depending on which was used would be worse
            // than one gate that was wrong consistently.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(goodPreview());
            ActivationDecision viaRegister =
                GATE.decideFromRegister(approvedCurveVersion(), EIGHT_YEAR_DRAFT, register, ACTIVATION);
            ActivationDecision direct =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, goodPreview(), ACTIVATION);
            assertThat(viaRegister).isEqualTo(direct);
        }

        @Test
        @DisplayName("a fully offsetting preview is permitted, and flagged loudly")
        void offsettingIsFlaggedNotRefused() {
            // Permitted because it is a real measurement honestly made: contracts moved and the
            // portfolio total nets to nothing. Flagged because the total alone reads as
            // immaterial, and a 12-crore single-contract restatement hidden inside a nil
            // portfolio figure is exactly the disclosure a checker must see.
            ImpactPreview offsetting = new ImpactPreview(
                VERSION_ID, EIGHT_YEAR_DRAFT, GENERATED, BOOK_AS_OF, 2L,
                Money.zero(Money.INR), Money.zero(Money.INR), Money.inr("120000000.00"),
                EIR_BEFORE, EIR_AFTER);
            ActivationDecision decision =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, offsetting, ACTIVATION);
            assertThat(decision.permitted()).isTrue();
            assertThat(decision.detail()).contains("FULLY OFFSETTING");
        }

        @Test
        @DisplayName("an unapproved version is not this gate's business")
        void approvalStatusIsNotThisGatesQuestion() {
            // Deliberate lane boundary. Whether a DRAFT may advance to EFFECTIVE, and whether a
            // checker distinct from the maker signed it, belong to the maker-checker transition.
            // This gate answers one question — is there a usable preview of this draft — and the
            // two compose by conjunction. Written this way so neither has to change when the
            // other does.
            PolicyVersion unapproved = new PolicyVersion(
                VERSION_ID, PolicyKind.BEHAVIOURAL_CURVE, "in progress",
                EFFECTIVE_FROM, "curve.owner", null, null, PolicyVersionStatus.DRAFT);
            ActivationDecision decision =
                GATE.decide(unapproved, EIGHT_YEAR_DRAFT, goodPreview(), ACTIVATION);
            assertThat(decision.permitted())
                .as("the preview is fine; the version's status is somebody else's gate")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("no preview at all — the honest failure")
    class NoPreview {

        @Test
        @DisplayName("a null preview refuses with NO_PREVIEW_STORED")
        void nullPreview() {
            ActivationDecision decision =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, null, ACTIVATION);
            assertThat(decision.isRefused()).isTrue();
            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.NO_PREVIEW_STORED);
            assertThat(decision.detail()).contains("FR-210");
        }

        @Test
        @DisplayName("an empty register refuses the same way")
        void emptyRegister() {
            ActivationDecision decision = GATE.decideFromRegister(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, ImpactPreviewRegister.empty(), ACTIVATION);
            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.NO_PREVIEW_STORED);
        }

        @Test
        @DisplayName("previews for other versions do not count as previews for this one")
        void otherVersionsDoNotCount() {
            // A preview of the routing table must never satisfy the gate for a curve revision.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                previewOf("RT-2027.1", EIGHT_YEAR_DRAFT, GENERATED, true));
            assertThat(GATE.decideFromRegister(approvedCurveVersion(), EIGHT_YEAR_DRAFT, register, ACTIVATION)
                .refusalReason())
                .contains(ActivationRefusalReason.NO_PREVIEW_STORED);
        }

        @Test
        @DisplayName("this is the one refusal that does not look like diligence")
        void doesNotLookLikeDiligence() {
            // The documented position of this package, asserted rather than left in prose. An
            // absent preview stops the version and shows the gap. Every other refusal describes
            // a stored record that reads, in the audit file, as evidence somebody quantified the
            // change — which is why the reasons are distinguished at all.
            assertThat(ActivationRefusalReason.NO_PREVIEW_STORED.looksLikeDiligence()).isFalse();
            for (ActivationRefusalReason reason : ActivationRefusalReason.values()) {
                if (reason != ActivationRefusalReason.NO_PREVIEW_STORED) {
                    assertThat(reason.looksLikeDiligence())
                        .as("%s describes a stored record", reason)
                        .isTrue();
                }
                assertThat(reason.blocksActivation())
                    .as("%s must block; 06 § 5 returns 409, not a warning header", reason)
                    .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("a preview of an earlier draft — worse than none, and named separately")
    class StaleDraft {

        @Test
        @DisplayName("a preview of the superseded draft is refused with no tolerance")
        void staleDraftIsRefused() {
            // The maker previewed the 240-month draft, then compressed assumed life to 96
            // months. 03 § 3.6: the preview's year-one figure is now wrong by 3.73x. The version
            // id did not change, so nothing about the stored preview looks stale.
            ImpactPreview previewOfOldDraft =
                previewOf(VERSION_ID, TWENTY_YEAR_DRAFT, GENERATED, true);
            ActivationDecision decision = GATE.decide(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, previewOfOldDraft, ACTIVATION);

            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.STALE_DRAFT_PREVIEW);
            assertThat(decision.refusalLooksLikeDiligence()).isTrue();
            assertThat(decision.detail())
                .contains("The version id is stable across draft edits")
                .contains(TWENTY_YEAR_DRAFT.abbreviated())
                .contains(EIGHT_YEAR_DRAFT.abbreviated());
        }

        @Test
        @DisplayName("the register form counts what is on file and says the newest is not it")
        void staleDraftFromRegister() {
            // Distinguishing this message from NO_PREVIEW_STORED is the point: the reader must
            // be sent to look at the draft, not at whether a preview exists. Three previews on
            // file, none of the current content.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                previewOf(VERSION_ID, TWENTY_YEAR_DRAFT, Instant.parse("2027-01-05T10:00:00Z"), true),
                previewOf(VERSION_ID, TWENTY_YEAR_DRAFT, Instant.parse("2027-02-01T10:00:00Z"), true),
                previewOf(VERSION_ID, TWENTY_YEAR_DRAFT, GENERATED, true));
            ActivationDecision decision =
                GATE.decideFromRegister(approvedCurveVersion(), EIGHT_YEAR_DRAFT, register, ACTIVATION);

            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.STALE_DRAFT_PREVIEW);
            assertThat(decision.detail())
                .contains("3 impact preview(s) are stored")
                .contains("the newest covers")
                .contains("2027-03-10T09:30:00Z")
                .contains("worse than none");
        }

        @Test
        @DisplayName("an older preview of the current draft is accepted; recency is not relevance")
        void revertedDraftKeepsItsPreview() {
            // Previewed at 240 months in January, edited to 96, reverted to 240 in March. The
            // newest preview covers an abandoned draft; the January one covers exactly what the
            // draft says now, and re-running it would produce identical figures. Accepted here
            // and still subject to the age horizon, which is the separate question.
            Instant january = Instant.parse("2027-01-05T10:00:00Z");
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                previewOf(VERSION_ID, TWENTY_YEAR_DRAFT, january, true),
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, GENERATED, true));
            ActivationDecision decision = GATE.decideFromRegister(
                approvedCurveVersion(), TWENTY_YEAR_DRAFT, register,
                january.plus(Duration.ofDays(30)));
            assertThat(decision.permitted()).isTrue();
            assertThat(decision.detail()).contains("30 days stale");
        }

        @Test
        @DisplayName("a preview of a different version is a different refusal again")
        void wrongVersionEntirely() {
            ImpactPreview otherVersion =
                previewOf("FEE-2027.1", EIGHT_YEAR_DRAFT, GENERATED, true);
            ActivationDecision decision =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, otherVersion, ACTIVATION);
            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_FOR_A_DIFFERENT_VERSION);
            assertThat(decision.detail())
                .contains("previews FEE-2027.1, not CURVE-2027.1");
        }
    }

    @Nested
    @DisplayName("selection from the register: a usable preview on file is not wasted")
    class RegisterSelection {

        @Test
        @DisplayName("an unusable newest preview does not block a usable older one")
        void fallsBackToAnOlderUsablePreview() {
            // Two previews of the current draft: a good one from 21 days ago and one stamped an
            // hour in the future off a skewed clock. Refusing on the newest alone would block the
            // version with a perfectly good preview sitting in the register — a false refusal,
            // and false refusals are how a hard gate gets argued down to a soft one.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                goodPreview(),
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, ACTIVATION.plus(Duration.ofHours(1)), true));

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                register.newestFor(VERSION_ID, EIGHT_YEAR_DRAFT).orElseThrow(), ACTIVATION)
                .refusalReason())
                .as("the newest matching preview, taken alone, is unusable")
                .contains(ActivationRefusalReason.PREVIEW_POSTDATES_ACTIVATION);

            ActivationDecision decision = GATE.decideFromRegister(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, register, ACTIVATION);
            assertThat(decision.permitted())
                .as("the gate walks the matching previews and takes the first that is usable")
                .isTrue();
            assertThat(decision.detail()).contains("21 days stale");
        }

        @Test
        @DisplayName("when none is usable, the refusal reported is the newest one's")
        void reportsTheNewestFailure() {
            // Both previews of the current draft are unusable — one incoherent from 21 days ago,
            // one future-stamped. The message names the newest attempt, because that is the one
            // the maker just made and will be looking at.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, GENERATED, false),
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, ACTIVATION.plus(Duration.ofHours(1)), true));
            assertThat(GATE.decideFromRegister(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, register, ACTIVATION).refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_POSTDATES_ACTIVATION);
        }
    }

    @Nested
    @DisplayName("a preview whose own figures do not tie")
    class Incoherent {

        @Test
        @DisplayName("zero contracts affected against a 6,884,990.00 movement is refused")
        void incoherentIsRefused() {
            ActivationDecision decision = GATE.decide(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, GENERATED, false), ACTIVATION);
            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.INCOHERENT_PREVIEW);
            assertThat(decision.detail())
                .contains("contradicts itself")
                .contains("no figure in it can be relied on");
        }
    }

    @Nested
    @DisplayName("the two time-order gates")
    class TimeOrder {

        @Test
        @DisplayName("a preview stamped after the activation it should have preceded")
        void postdatesActivation() {
            // Either the clock is skewed — in which case the preview's age cannot be assessed
            // either — or it was written after the fact. Both make it inadmissible as evidence
            // that the change was quantified beforehand.
            ImpactPreview future = previewOf(
                VERSION_ID, EIGHT_YEAR_DRAFT, ACTIVATION.plus(Duration.ofHours(1)), true);
            ActivationDecision decision =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, future, ACTIVATION);
            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_POSTDATES_ACTIVATION);
        }

        @Test
        @DisplayName("generated after the approval day had ended everywhere: refused")
        void postdatesApproval() {
            // 07 § 4.2 requires the preview to precede approval. Approved 2027-03-15, so the
            // bound is midnight starting 16 March at UTC-12 = 2027-03-16T12:00:00Z; at or past
            // that instant no real jurisdiction's clock could still call it the 15th.
            ImpactPreview justPast =
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, END_OF_APPROVAL_DAY_ANYWHERE, true);
            ActivationDecision decision =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, justPast, ACTIVATION);
            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_POSTDATES_APPROVAL);
            assertThat(decision.detail())
                .contains("after the approval dated 2027-03-15")
                .contains("in every real time zone");
        }

        @Test
        @DisplayName("one second inside that bound is permitted, because the zone is unknown")
        void sameDayAsApprovalIsPermitted() {
            // The check is deliberately conservative. A record carrying a date and an instant
            // cannot say which zone the date was written in, and refusing a genuine same-day
            // preview would be a hard gate producing false refusals — which is how a hard gate
            // gets argued down to a soft one.
            ImpactPreview lastMoment = previewOf(
                VERSION_ID, EIGHT_YEAR_DRAFT,
                END_OF_APPROVAL_DAY_ANYWHERE.minusSeconds(1), true);
            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, lastMoment, ACTIVATION)
                .permitted())
                .isTrue();
        }

        @Test
        @DisplayName("a future stamp is reported as postdating the activation, not the approval")
        void activationOrderIsReportedBeforeApprovalOrder() {
            // A preview stamped after the activation also postdates the approval. The message
            // must name the activation, because that is the fault the reader can act on: the
            // approval-order breach is a consequence of the same bad timestamp.
            ImpactPreview future = previewOf(
                VERSION_ID, EIGHT_YEAR_DRAFT, ACTIVATION.plus(Duration.ofDays(1)), true);
            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, future, ACTIVATION)
                .refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_POSTDATES_ACTIVATION);
        }
    }

    @Nested
    @DisplayName("the age horizon — staleness against the book, not the draft")
    class AgeHorizon {

        @Test
        @DisplayName("the default horizon is 90 days, one reporting quarter")
        void defaultHorizon() {
            assertThat(ActivationGate.DEFAULT_PREVIEW_HORIZON).isEqualTo(Duration.ofDays(90));
            assertThat(GATE.previewHorizon()).isEqualTo(Duration.ofDays(90));
        }

        @Test
        @DisplayName("exactly at the horizon is permitted; one second past is refused")
        void horizonBoundary() {
            // 2027-04-01T02:00:00Z less 90 days is 2027-01-01T02:00:00Z — inside the horizon by
            // construction, and before the approval date, so nothing else can refuse it.
            ImpactPreview atHorizon = previewOf(
                VERSION_ID, EIGHT_YEAR_DRAFT, ACTIVATION.minus(Duration.ofDays(90)), true);
            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, atHorizon, ACTIVATION)
                .permitted())
                .as("a boundary that refused at exactly the horizon would make the stated"
                    + " horizon one second shorter than the number in the policy")
                .isTrue();

            ImpactPreview pastHorizon = previewOf(
                VERSION_ID, EIGHT_YEAR_DRAFT,
                ACTIVATION.minus(Duration.ofDays(90)).minusSeconds(1), true);
            ActivationDecision refused =
                GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, pastHorizon, ACTIVATION);
            assertThat(refused.refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_OLDER_THAN_HORIZON);
            assertThat(refused.detail())
                .contains("matches the current draft")
                .contains("against a horizon of 90 days");
        }

        @Test
        @DisplayName("a preview re-run today against a year-old book is still stale")
        void freshComputationAgainstAStaleBook() {
            // The gap between the two anchors, and it is exploitable without anybody intending
            // it: pull last year's portfolio extract, re-run the preview this morning, and the
            // stored record is minutes old while every figure in it describes a book that no
            // longer exists. 02 § 3.2 requires the preview to be run "against the live
            // portfolio", and portfolioAsOf is the only field that records which book it saw.
            //
            // Generated 2027-04-01T01:00:00Z — one hour before the activation — against the book
            // at 2026-03-31. The book anchor is the first instant after that date anywhere,
            // 2026-04-01T12:00:00Z, so the staleness is 365 days: 2026-04-01 to 2027-04-01 with
            // 2027 not a leap year, less the ten hours from 12:00Z to 02:00Z, so 364 whole days.
            ImpactPreview freshButStale = new ImpactPreview(
                VERSION_ID, EIGHT_YEAR_DRAFT, Instant.parse("2027-04-01T01:00:00Z"),
                LocalDate.of(2026, 3, 31), 1_000L,
                PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);

            assertThat(freshButStale.ageAt(ACTIVATION))
                .as("one hour old by its generation instant, which is why that is not the test")
                .isEqualTo(Duration.ofHours(1));
            assertThat(freshButStale.stalenessAt(ACTIVATION).toDays()).isEqualTo(364L);

            // Asserted against a version with no approval date, so that the horizon is the only
            // gate in play: a preview generated on 1 April also postdates a 15 March approval,
            // and that earlier check would mask the axis under test.
            PolicyVersion unapproved = new PolicyVersion(
                VERSION_ID, PolicyKind.BEHAVIOURAL_CURVE, "awaiting a checker",
                EFFECTIVE_FROM, "curve.owner", null, null, PolicyVersionStatus.PENDING_APPROVAL);
            ActivationDecision decision =
                GATE.decide(unapproved, EIGHT_YEAR_DRAFT, freshButStale, ACTIVATION);
            assertThat(decision.refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_OLDER_THAN_HORIZON);
            assertThat(decision.detail())
                .contains("364 days stale")
                .contains("against the book at 2026-03-31");
        }

        @Test
        @DisplayName("the horizon is a policy number, so a tighter gate is configurable")
        void configurableHorizon() {
            // Configurable because it is a policy judgement, and policy judgements in this
            // engine are data rather than constants in a class file (ADR-0006). A bank closing
            // monthly can hold previews to a month.
            ActivationGate monthly = new ActivationGate(Duration.ofDays(30));
            assertThat(monthly.decide(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, goodPreview(), ACTIVATION).permitted())
                .as("21 days stale, inside a 30-day horizon")
                .isTrue();
            assertThat(monthly.decide(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, goodPreview(),
                GENERATED.plus(Duration.ofDays(31))).refusalReason())
                .contains(ActivationRefusalReason.PREVIEW_OLDER_THAN_HORIZON);
        }

        @Test
        @DisplayName("a non-positive horizon is refused at construction")
        void nonPositiveHorizonIsRefused() {
            // A zero or negative horizon refuses every preview ever generated, including one
            // taken a second ago, and a gate nobody can satisfy gets switched off rather than
            // fixed.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ActivationGate(Duration.ZERO))
                .withMessageContaining("must be positive");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ActivationGate(Duration.ofDays(-1)))
                .withMessageContaining("refuses every preview");
        }
    }

    @Nested
    @DisplayName("refusal priority: the most fundamental fault is the one reported")
    class Priority {

        @Test
        @DisplayName("faults are peeled off in order, wrong version to merely old")
        void cascade() {
            // One preview, six faults, fixed one at a time. The order runs from "this is not a
            // preview of this thing" to "this is a preview of this thing, taken too long ago",
            // because a message naming the age of a preview that describes a different draft
            // sends its reader to fix the wrong problem.
            Instant future = ACTIVATION.plus(Duration.ofHours(1));

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                previewOf("FEE-2027.1", TWENTY_YEAR_DRAFT, future, false), ACTIVATION)
                .refusalReason())
                .as("wrong version, wrong draft, incoherent, future-stamped")
                .contains(ActivationRefusalReason.PREVIEW_FOR_A_DIFFERENT_VERSION);

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                previewOf(VERSION_ID, TWENTY_YEAR_DRAFT, future, false), ACTIVATION)
                .refusalReason())
                .as("version fixed; wrong draft, incoherent, future-stamped")
                .contains(ActivationRefusalReason.STALE_DRAFT_PREVIEW);

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, future, false), ACTIVATION)
                .refusalReason())
                .as("draft fixed; incoherent and future-stamped")
                .contains(ActivationRefusalReason.INCOHERENT_PREVIEW);

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, future, true), ACTIVATION)
                .refusalReason())
                .as("figures fixed; still future-stamped")
                .contains(ActivationRefusalReason.PREVIEW_POSTDATES_ACTIVATION);

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT, END_OF_APPROVAL_DAY_ANYWHERE, true),
                ACTIVATION).refusalReason())
                .as("stamp brought back before the activation, still after approval")
                .contains(ActivationRefusalReason.PREVIEW_POSTDATES_APPROVAL);

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT,
                previewOf(VERSION_ID, EIGHT_YEAR_DRAFT,
                    ACTIVATION.minus(Duration.ofDays(91)), true),
                ACTIVATION).refusalReason())
                .as("order fixed; only the age is left")
                .contains(ActivationRefusalReason.PREVIEW_OLDER_THAN_HORIZON);

            assertThat(GATE.decide(approvedCurveVersion(), EIGHT_YEAR_DRAFT, goodPreview(),
                ACTIVATION).permitted())
                .as("everything fixed")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("every kind of policy is gated, because every kind moves income")
    class Kinds {

        @Test
        @DisplayName("no kind is exempt in this build")
        void noKindIsExempt() {
            // The gate asks PolicyKind.movesRecognisedIncome() rather than assuming, which is
            // what that method is for. Asserted across the enum so that adding a kind forces a
            // decision about whether it needs a preview instead of inheriting one silently — and
            // so that if some future kind is made exempt, this test is where that shows up.
            for (PolicyKind kind : PolicyKind.values()) {
                PolicyVersion version = new PolicyVersion(
                    "V-" + kind, kind, "a change of " + kind, EFFECTIVE_FROM,
                    "maker", "checker", APPROVED_ON, PolicyVersionStatus.APPROVED);
                ActivationDecision decision =
                    GATE.decide(version, EIGHT_YEAR_DRAFT, null, ACTIVATION);
                assertThat(decision.refusalReason())
                    .as("%s must not go effective unpreviewed", kind)
                    .contains(ActivationRefusalReason.NO_PREVIEW_STORED);
            }
        }
    }

    @Nested
    @DisplayName("the decision value itself")
    class DecisionValue {

        @Test
        @DisplayName("a decision cannot say permitted and also name a refusal")
        void halvesMustAgree() {
            // A caller reads whichever half it trusts. One that trusts the boolean would let a
            // named refusal through; one that trusts the reason would block a permit. Neither
            // shape is representable.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ActivationDecision(
                    true, ActivationRefusalReason.NO_PREVIEW_STORED, VERSION_ID, "d"))
                .withMessageContaining("cannot also carry the refusal");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ActivationDecision(false, null, VERSION_ID, "d"))
                .withMessageContaining("must name its reason");
        }

        @Test
        @DisplayName("the audit sentence names the outcome, the version and the reason")
        void describe() {
            assertThat(ActivationDecision.refuse(
                VERSION_ID, ActivationRefusalReason.STALE_DRAFT_PREVIEW, "the draft moved")
                .describe())
                .isEqualTo("ACTIVATION REFUSED (STALE_DRAFT_PREVIEW) for policy version"
                    + " CURVE-2027.1: the draft moved");
        }

        @Test
        @DisplayName("null arguments are programming errors, not data conditions")
        void nullsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> GATE.decide(
                null, EIGHT_YEAR_DRAFT, goodPreview(), ACTIVATION));
            assertThatNullPointerException().isThrownBy(() -> GATE.decide(
                approvedCurveVersion(), null, goodPreview(), ACTIVATION));
            assertThatNullPointerException().isThrownBy(() -> GATE.decide(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, goodPreview(), null));
            assertThatNullPointerException().isThrownBy(() -> GATE.decideFromRegister(
                approvedCurveVersion(), EIGHT_YEAR_DRAFT, null, ACTIVATION));
        }
    }
}
