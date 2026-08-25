package com.crisil.eir.policy.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What "stored" means to the gate, and — the part worth testing — which of several stored
 * previews it selects.
 *
 * <p>Several previews per version id is the normal case: a draft is revised, re-previewed,
 * revised again. The selection rule decides whether the gate can be satisfied by the wrong one,
 * so it is asserted directly rather than through the gate.
 */
class ImpactPreviewRegisterTest {

    private static final String VERSION_ID = "CURVE-2027.1";
    private static final DraftFingerprint TWENTY_YEAR_DRAFT =
        DraftFingerprint.of("EXPECTED_LIFE_MONTHS=240");
    private static final DraftFingerprint EIGHT_YEAR_DRAFT =
        DraftFingerprint.of("EXPECTED_LIFE_MONTHS=96");
    private static final Rate EIR_BEFORE = Rate.annualEffective(new BigDecimal("0.09533867"));
    private static final Rate EIR_AFTER = Rate.annualEffective(new BigDecimal("0.09689903"));

    /**
     * The book position is derived from the generation instant — the previous day — because a
     * preview cannot have measured a book that had not begun when it ran, and a fixed date would
     * make the earlier-dated fixtures here incoherent for a reason none of these tests is about.
     */
    private static ImpactPreview preview(String versionId, DraftFingerprint draft, String at) {
        Instant generatedAt = Instant.parse(at);
        LocalDate bookAsOf = generatedAt.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        return new ImpactPreview(
            versionId, draft, generatedAt, bookAsOf, 1_000L,
            Money.inr("6884990.00"), Money.inr("6884990.00"), Money.inr("6884.99"),
            EIR_BEFORE, EIR_AFTER);
    }

    @Nested
    @DisplayName("an empty register")
    class Empty {

        @Test
        @DisplayName("holds nothing and says so for any version")
        void holdsNothing() {
            ImpactPreviewRegister empty = ImpactPreviewRegister.empty();
            assertThat(empty.hasAnyFor(VERSION_ID)).isFalse();
            assertThat(empty.storedFor(VERSION_ID)).isEmpty();
            assertThat(empty.newestFor(VERSION_ID)).isEmpty();
            assertThat(empty.newestFor(VERSION_ID, EIGHT_YEAR_DRAFT)).isEmpty();
            assertThat(empty.toString()).isEqualTo("impact preview register: empty");
        }

        @Test
        @DisplayName("a version nobody previewed is not an error")
        void unknownVersion() {
            // Returned as an empty list rather than null, because the gate's next move is to
            // count what is there and a null would turn a refusal into a crash on the one path
            // that must always produce a reasoned answer.
            ImpactPreviewRegister register =
                ImpactPreviewRegister.of(preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z"));
            assertThat(register.storedFor("FEE-2027.1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("storing is a copy, never a mutation")
    class Immutability {

        @Test
        @DisplayName("with() leaves the original register untouched")
        void withDoesNotMutate() {
            // The gate's answer is an audit record. A mutable store would make that answer
            // depend on what some other thread did between the two reads inside one decision.
            ImpactPreviewRegister first =
                ImpactPreviewRegister.of(preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-01-05T10:00:00Z"));
            ImpactPreviewRegister second =
                first.with(preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z"));

            assertThat(first.storedFor(VERSION_ID)).hasSize(1);
            assertThat(second.storedFor(VERSION_ID)).hasSize(2);
            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("the returned list cannot be added to")
        void storedListIsUnmodifiable() {
            ImpactPreviewRegister register =
                ImpactPreviewRegister.of(preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z"));
            assertThatThrownBy(() -> register.storedFor(VERSION_ID)
                .add(preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-04-01T00:00:00Z")))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null preview is a programming error")
        void nullIsRefused() {
            assertThatNullPointerException()
                .isThrownBy(() -> ImpactPreviewRegister.empty().with(null));
        }
    }

    @Nested
    @DisplayName("selection: which of several previews the gate gets")
    class Selection {

        @Test
        @DisplayName("newest by generation instant, not by insertion order")
        void newestByGeneratedAt() {
            // Stored out of order on purpose. Insertion order is a fact about how a row set was
            // read; the preview's own timestamp is the fact about the book.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z"),
                preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-01-05T10:00:00Z"));
            assertThat(register.newestFor(VERSION_ID)).isPresent();
            assertThat(register.newestFor(VERSION_ID).orElseThrow().generatedAt())
                .isEqualTo(Instant.parse("2027-03-10T09:30:00Z"));
        }

        @Test
        @DisplayName("content match first, then recency — so a reverted draft keeps its preview")
        void matchThenRecency() {
            // The scenario: previewed at 240 months, edited to 96, then reverted to 240. The
            // newest preview overall covers the 96-month draft and is NOT the current content;
            // the older one covers exactly what the draft says now. Selecting newest-then-check
            // would refuse a maker who has a valid preview of the current content and send them
            // to re-run a computation whose figures would be identical.
            ImpactPreview atTwentyYears = preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-01-05T10:00:00Z");
            ImpactPreview atEightYears = preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z");
            ImpactPreviewRegister register =
                ImpactPreviewRegister.of(atTwentyYears, atEightYears);

            assertThat(register.newestFor(VERSION_ID))
                .as("newest overall covers the abandoned 96-month draft")
                .contains(atEightYears);
            assertThat(register.newestFor(VERSION_ID, TWENTY_YEAR_DRAFT))
                .as("newest covering the draft as it now stands")
                .contains(atTwentyYears);
        }

        @Test
        @DisplayName("no preview of the current draft, even with several stored")
        void noMatchingPreview() {
            // The state that produces STALE_DRAFT_PREVIEW: three previews on file for this
            // version, none of them of what it says now.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-01-05T10:00:00Z"),
                preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-02-01T10:00:00Z"),
                preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-02-20T10:00:00Z"));
            assertThat(register.hasAnyFor(VERSION_ID)).isTrue();
            assertThat(register.newestFor(VERSION_ID, EIGHT_YEAR_DRAFT)).isEmpty();
        }

        @Test
        @DisplayName("a tie on generation instant resolves to the later-stored preview, stably")
        void tieBrokenByStorageOrder() {
            // Reachable from two previews generated inside one clock tick, or from a replayed
            // fixture carrying identical timestamps. Which way the tie goes matters less than
            // that it goes the same way every run: the gate's audit sentence quotes the selected
            // preview, and invariant DT-1 requires a replay to reproduce it.
            ImpactPreview first = preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z");
            ImpactPreview second = new ImpactPreview(
                VERSION_ID, EIGHT_YEAR_DRAFT, Instant.parse("2027-03-10T09:30:00Z"),
                LocalDate.of(2027, 3, 9),
                2_000L, Money.inr("13769980.00"), Money.inr("13769980.00"), Money.inr("6884.99"),
                EIR_BEFORE, EIR_AFTER);
            ImpactPreviewRegister register = ImpactPreviewRegister.of(first, second);

            assertThat(register.newestFor(VERSION_ID)).contains(second);
            assertThat(ImpactPreviewRegister.of(second, first).newestFor(VERSION_ID))
                .as("the rule is storage order, so reversing the input reverses the winner")
                .contains(first);
        }

        @Test
        @DisplayName("matchingFor lists every preview of the draft, newest first")
        void matchingForIsOrderedNewestFirst() {
            // The gate walks this list rather than taking its head, so the order is load-bearing:
            // it decides which refusal is reported when none of them is usable, and an unstable
            // order would make the same stored data produce different audit sentences on a
            // replay (invariant DT-1).
            ImpactPreview january = preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-01-05T10:00:00Z");
            ImpactPreview february = preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-02-01T10:00:00Z");
            ImpactPreview march = preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z");
            ImpactPreview otherDraft = preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-03-20T10:00:00Z");
            ImpactPreviewRegister register =
                ImpactPreviewRegister.of(february, otherDraft, march, january);

            assertThat(register.matchingFor(VERSION_ID, EIGHT_YEAR_DRAFT))
                .as("only the matching draft, newest first, regardless of storage order")
                .containsExactly(march, february, january);
            assertThat(register.matchingFor(VERSION_ID, TWENTY_YEAR_DRAFT))
                .containsExactly(otherDraft);
        }

        @Test
        @DisplayName("previews are kept per version, never pooled")
        void versionsAreSeparate() {
            // A preview of the routing table must never satisfy the gate for a fee rule set.
            // PolicyKind exists so the kinds move on separate clocks; pooling previews here
            // would undo that at the last step.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z"),
                preview("RT-2027.1", EIGHT_YEAR_DRAFT, "2027-03-11T09:30:00Z"));
            assertThat(register.storedFor(VERSION_ID)).hasSize(1);
            assertThat(register.storedFor("RT-2027.1")).hasSize(1);
            assertThat(register.toString()).contains("CURVE-2027.1 x1", "RT-2027.1 x1");
        }

        @Test
        @DisplayName("the whole revision history is retained, not just the latest")
        void historyIsRetained() {
            // The sequence is the evidence of how the change was arrived at. Keeping only the
            // latest would also discard the record that a larger impact was seen first and the
            // draft narrowed in response — which is the part a checker most needs.
            ImpactPreviewRegister register = ImpactPreviewRegister.of(
                preview(VERSION_ID, TWENTY_YEAR_DRAFT, "2027-01-05T10:00:00Z"),
                preview(VERSION_ID, EIGHT_YEAR_DRAFT, "2027-03-10T09:30:00Z"));
            assertThat(register.storedFor(VERSION_ID))
                .as("in storage order, oldest first")
                .hasSize(2);
            assertThat(register.storedFor(VERSION_ID).get(0).draftFingerprint())
                .isEqualTo(TWENTY_YEAR_DRAFT);
        }
    }
}
