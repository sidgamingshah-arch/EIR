package com.crisil.eir.policy.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The stored portfolio-level impact preview of FR-210.
 *
 * <p><b>The fixture is 03 § 3.6's own worked figures</b>, so that every expected value in this
 * file is traceable to the specification rather than to a run of the code. That section prices
 * one exposure under two assumed lives:
 *
 * <pre>
 *   assumed life 240 months  EIR 9.533867% p.a.  year-1 net fee recognised 2,525.04
 *   assumed life  96 months  EIR 9.689903% p.a.  year-1 net fee recognised 9,410.03
 * </pre>
 *
 * <p>9,410.03 / 2,525.04 = 3.7267…, the <b>3.73x</b> leverage the roadmap risk register names as
 * the UK restatement pattern. The preview under test is that change applied to a thousand
 * identical such contracts, which makes every portfolio figure a product of a documented
 * per-contract figure and a round count — derivable by hand, and stated at each assertion:
 *
 * <pre>
 *   per contract   9,410.03 - 2,525.04            =     6,884.99
 *   portfolio      6,884.99 x 1,000               = 6,884,990.00
 *   EIR shift      (0.09689903 - 0.09533867) x 1e4 =      15.6036 bps
 *   concentration  6,884.99 / 6,884,990.00        =        0.001  (= 1/1000)
 * </pre>
 */
class ImpactPreviewTest {

    private static final String VERSION_ID = "CURVE-2027.1";
    private static final DraftFingerprint DRAFT =
        DraftFingerprint.of("EXPECTED_LIFE_MONTHS=96", "CPR=12");
    private static final Instant GENERATED = Instant.parse("2027-03-10T09:30:00Z");
    private static final LocalDate BOOK_AS_OF = LocalDate.of(2027, 2, 28);

    /** 9.533867% effective p.a., the 240-month assumed life of 03 § 3.6. */
    private static final Rate EIR_BEFORE = Rate.annualEffective(new BigDecimal("0.09533867"));

    /** 9.689903% effective p.a., the 96-month assumed life of 03 § 3.6. */
    private static final Rate EIR_AFTER = Rate.annualEffective(new BigDecimal("0.09689903"));

    /** 9,410.03 - 2,525.04, the year-one fee movement on one exposure. */
    private static final Money PER_CONTRACT = Money.inr("6884.99");

    /** 6,884.99 x 1,000 identical contracts. */
    private static final Money PORTFOLIO = Money.inr("6884990.00");

    private static ImpactPreview curveRevision() {
        return new ImpactPreview(
            VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF,
            1_000L, PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
    }

    @Nested
    @DisplayName("the figures the preview is for")
    class DerivedFigures {

        @Test
        @DisplayName("the fixture's per-contract movement is 03 § 3.6's own arithmetic")
        void fixtureTiesToTheSpecification() {
            // Asserted so the rest of this file rests on a stated derivation rather than on a
            // number somebody typed. 9,410.03 - 2,525.04 = 6,884.99, and 6,884.99 x 1,000 is the
            // portfolio total used throughout.
            assertThat(Money.inr("9410.03").minus(Money.inr("2525.04")))
                .as("year-one net fee recognised at 96 months less at 240 months")
                .isEqualTo(PER_CONTRACT);
            assertThat(PER_CONTRACT.times(new BigDecimal("1000")))
                .as("one thousand identical contracts")
                .isEqualTo(PORTFOLIO);
        }

        @Test
        @DisplayName("the weighted-average EIR shift is 15.6036 bps")
        void eirShiftInBasisPoints() {
            // (0.09689903 - 0.09533867) x 10,000 = 15.6036. Basis points of EFFECTIVE annual
            // rate: at one period a year the effective annual rate is the periodic rate, so the
            // subtraction is the whole derivation. Reported because a portfolio whose carrying
            // amount barely moves in the period of the change can still have been repriced for
            // the whole remaining life of every contract in it.
            assertThat(curveRevision().weightedAverageEirShiftBps())
                .as("15.6036 basis points, from the two rates in 03 § 3.6")
                .isEqualByComparingTo(new BigDecimal("15.6036"));
        }

        @Test
        @DisplayName("concentration is the largest single contract over the portfolio total")
        void concentration() {
            // A thousand identical contracts, so exactly 1/1000 = 0.001. The figure exists to
            // catch the opposite case: a ratio near 1 means the portfolio number IS one
            // exposure, and a preview reporting only the total would read as diversified.
            assertThat(curveRevision().concentration()).isPresent();
            assertThat(curveRevision().concentration().orElseThrow())
                .as("6,884.99 / 6,884,990.00 = 1/1000")
                .isEqualByComparingTo(new BigDecimal("0.001"));
        }

        @Test
        @DisplayName("concentration is undefined, not zero, when the portfolio total nets out")
        void concentrationOnAnOffsettingPortfolio() {
            // Returning zero here would be the worst available answer: it reads as "no
            // concentration" on precisely the portfolio where the total conceals everything.
            ImpactPreview offsetting = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 2L,
                Money.zero(Money.INR), Money.zero(Money.INR), Money.inr("120000000.00"),
                EIR_BEFORE, EIR_AFTER);
            assertThat(offsetting.concentration()).isEmpty();
            assertThat(offsetting.isFullyOffsetting())
                .as("two contracts moved, the portfolio total did not")
                .isTrue();
            assertThat(offsetting.describe())
                .as("the audit sentence must say so; the total alone reads as immaterial")
                .contains("FULLY OFFSETTING");
        }

        @Test
        @DisplayName("netting conceals movement whenever one contract beats the whole total")
        void nearOffsettingIsFlaggedToo() {
            // The general form of the offsetting check, and the case an exactly-zero test misses.
            // A portfolio net of 1.00 against a single contract moving 12,00,00,000.00 is the
            // same concealment as a portfolio net of nil: the total is the number that gets
            // quoted, and quoting it alone understates the gross by eight orders of magnitude.
            // Threshold-free — if one contract moves more than the portfolio nets, others must be
            // moving the other way — so it is flagged and not refused; materiality is a Board
            // number (03 § 10), not this record's to decide.
            ImpactPreview nearlyOffsetting = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 2L,
                Money.inr("1.00"), Money.inr("1.00"), Money.inr("120000000.00"),
                EIR_BEFORE, EIR_AFTER);
            assertThat(nearlyOffsetting.isFullyOffsetting())
                .as("not the exactly-zero case")
                .isFalse();
            assertThat(nearlyOffsetting.concealsOffsettingMovement()).isTrue();
            assertThat(nearlyOffsetting.isCoherent())
                .as("nothing here contradicts itself; it is honest and misleading")
                .isTrue();
            assertThat(nearlyOffsetting.describe())
                .contains("NETTING CONCEALS MOVEMENT")
                .contains("one contract moves INR 120000000.00")
                .contains("against a portfolio net of INR 1.00");

            assertThat(curveRevision().concealsOffsettingMovement())
                .as("6,884.99 against a 6,884,990.00 total is not concealment")
                .isFalse();
        }

        @Test
        @DisplayName("staleness takes the older of the computation and the book it measured")
        void stalenessAnchorsOnTheBookWhenThatIsOlder() {
            // The two anchors come apart, and the horizon has to follow the older one. This
            // fixture was generated 2027-03-10T09:30Z against the book at 2027-02-28; the book
            // date is widened to the first instant after it anywhere on earth, midnight starting
            // 1 March at UTC-12 = 2027-03-01T12:00:00Z, which is the older anchor.
            //
            // 2027-03-01T12:00Z to 2027-04-01T02:00Z is 31 days less 10 hours = 30 whole days,
            // against a generation age of 21 days.
            Instant activation = Instant.parse("2027-04-01T02:00:00Z");
            assertThat(curveRevision().ageAt(activation).toDays()).isEqualTo(21L);
            assertThat(curveRevision().stalenessAt(activation).toDays())
                .as("measured from the book position, which is 9 days older than the computation")
                .isEqualTo(30L);
            assertThat(curveRevision().stalenessAnchor())
                .isEqualTo(Instant.parse("2027-03-01T12:00:00Z"));
        }

        @Test
        @DisplayName("staleness falls back to the computation when the book is newer")
        void stalenessAnchorsOnGenerationWhenTheBookIsNewer() {
            // Generated 2027-03-10T09:30Z against the book at 2027-03-09: the book anchor is
            // 2027-03-10T12:00Z, later than the generation instant, so the generation instant is
            // the older of the two and the staleness is the plain age, 21 days.
            ImpactPreview sameDayBook = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, LocalDate.of(2027, 3, 9), 1_000L,
                PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
            Instant activation = Instant.parse("2027-04-01T02:00:00Z");
            assertThat(sameDayBook.stalenessAnchor()).isEqualTo(GENERATED);
            assertThat(sameDayBook.stalenessAt(activation).toDays()).isEqualTo(21L);
        }

        @Test
        @DisplayName("a nil preview is a quantified claim, not an absent one")
        void nilPreview() {
            // A clarifying rewording that maps no live fee code genuinely previews to nothing.
            // That must remain expressible, because a nil preview somebody signed is different
            // in kind from no preview at all — which is the distinction the whole gate turns on.
            ImpactPreview nil = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 0L,
                Money.zero(Money.INR), Money.zero(Money.INR), Money.zero(Money.INR),
                EIR_BEFORE, EIR_BEFORE);
            assertThat(nil.isNoMovement()).isTrue();
            assertThat(nil.isCoherent()).isTrue();
            assertThat(nil.describe()).contains("NO MOVEMENT");

            assertThat(curveRevision().isNoMovement()).isFalse();
            assertThat(curveRevision().isFullyOffsetting())
                .as("a 6,884,990.00 portfolio movement is not offsetting")
                .isFalse();
        }

        @Test
        @DisplayName("age is measured from generation to the instant asked about")
        void age() {
            // Generated 2027-03-10T09:30Z, asked at 2027-04-01T02:00Z. 10 March to 1 April is
            // 22 days; 09:30 to 02:00 is 7.5 hours short of the last of them, so 21 whole days.
            Instant activation = Instant.parse("2027-04-01T02:00:00Z");
            assertThat(curveRevision().ageAt(activation).toDays()).isEqualTo(21L);
            assertThat(curveRevision().ageAt(activation))
                .isEqualTo(Duration.ofDays(21).plusHours(16).plusMinutes(30));
        }

        @Test
        @DisplayName("the audit sentence states every figure a reader would otherwise fetch")
        void describe() {
            String sentence = curveRevision().describe();
            assertThat(sentence)
                .contains(VERSION_ID)
                .contains(DRAFT.abbreviated())
                .contains("2027-03-10T09:30:00Z")
                .contains("against the book at 2027-02-28")
                .contains("1000 contracts affected")
                .contains("INR 6884990.00")
                .contains("INR 6884.99")
                .contains("953.3867 to 968.9903 bps (+15.6036)");
            assertThat(sentence).doesNotContain("INCOHERENT");
        }

        @Test
        @DisplayName("matching is on version id and on draft content, separately")
        void matching() {
            ImpactPreview preview = curveRevision();
            assertThat(preview.previews(VERSION_ID)).isTrue();
            assertThat(preview.previews("FEE-2027.1")).isFalse();
            assertThat(preview.coversDraft(DRAFT)).isTrue();
            assertThat(preview.coversDraft(DraftFingerprint.of("EXPECTED_LIFE_MONTHS=240", "CPR=12")))
                .as("the same version id, an earlier draft of it")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("figures that contradict each other are reported, never thrown")
    class Incoherence {

        @Test
        @DisplayName("the fixture is coherent, so the checks are not vacuous")
        void theFixtureIsCoherent() {
            assertThat(curveRevision().incoherences()).isEmpty();
            assertThat(curveRevision().isCoherent()).isTrue();
        }

        @Test
        @DisplayName("no contracts affected, yet the portfolio moved")
        void movementWithoutContracts() {
            // The defect: an "affected" filter applied when counting but not when summing, so
            // the count is taken over one population and the total over another. Both figures
            // look plausible alone.
            ImpactPreview broken = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 0L,
                PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
            assertThat(broken.incoherences()).hasSize(1);
            assertThat(broken.incoherences().get(0))
                .contains("no contracts affected, yet the preview reports movement");
            assertThat(broken.describe()).contains("INCOHERENT");
        }

        @Test
        @DisplayName("contracts affected, yet nothing moved")
        void contractsWithoutMovement() {
            // The mirror of the above, and the more dangerous half: the same aggregation defect
            // with the filter dropped from the summing leg instead of the counting leg. A nil
            // movement across a thousand contracts reads as a change with no consequences, which
            // is a conclusion a checker would act on. Without this check the audit sentence
            // carries no marker at all — isNoMovement requires a nil count, so it would read
            // "1000 contracts affected, gross carrying amount INR 0.00" and look deliberate.
            ImpactPreview broken = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1_000L,
                Money.zero(Money.INR), Money.zero(Money.INR), Money.zero(Money.INR),
                EIR_BEFORE, EIR_BEFORE);
            assertThat(broken.isNoMovement())
                .as("not caught by the nil-preview flag, which requires a nil count")
                .isFalse();
            assertThat(broken.incoherences()).hasSize(1);
            assertThat(broken.incoherences().get(0))
                .contains("1000 contracts affected, yet no figure in the preview moves");
        }

        @Test
        @DisplayName("a book position that had not begun when the preview ran")
        void bookPositionAfterGeneration() {
            // A preview cannot have measured a book that did not yet exist; what it measured was
            // a projection, and FR-210 asks for the portfolio rather than a forecast of it.
            // Generated 2027-03-10T09:30Z against a book at 2030-12-31 — and because the
            // staleness horizon takes the OLDER anchor, nothing downstream would catch this.
            ImpactPreview broken = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, LocalDate.of(2030, 12, 31), 1_000L,
                PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
            assertThat(broken.incoherences()).hasSize(1);
            assertThat(broken.incoherences().get(0))
                .contains("had not begun in any time zone");
        }

        @Test
        @DisplayName("a same-day book pull is never flagged, whatever the time zone")
        void sameDayBookPullIsCoherent() {
            // The check is deliberately conservative. Generated 2027-03-10T00:30Z — 06:00 IST on
            // the 10th — against the book as at the 10th. The book date is widened to the first
            // instant it begins anywhere, midnight on the 10th at UTC+14 = 2027-03-09T10:00:00Z,
            // which precedes the generation instant, so nothing is flagged. A UTC-assuming check
            // would be fine here but would refuse the mirror case at 21:30 UTC on the 9th, and a
            // hard gate producing false refusals is a hard gate that gets argued down.
            ImpactPreview sameDay = new ImpactPreview(
                VERSION_ID, DRAFT, Instant.parse("2027-03-10T00:30:00Z"),
                LocalDate.of(2027, 3, 10), 1_000L,
                PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
            assertThat(sameDay.incoherences()).isEmpty();
        }

        @Test
        @DisplayName("the portfolio moved but no single contract did")
        void portfolioMovesWithNoLargestMovement() {
            // The sum of a set of zeros is zero, so a non-zero total with a nil largest movement
            // means the largest was never computed — it defaulted. Caught because a defaulted
            // largest movement makes every concentrated change look diversified.
            ImpactPreview broken = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1_000L,
                PORTFOLIO, PORTFOLIO, Money.zero(Money.INR), EIR_BEFORE, EIR_AFTER);
            assertThat(broken.incoherences()).hasSize(1);
            assertThat(broken.incoherences().get(0))
                .contains("the sum of a set of zeros is zero");
        }

        @Test
        @DisplayName("one contract affected, and the two figures are not the same figure")
        void singleContractMustEqualThePortfolio() {
            // The sharpest check available: with one contract in the affected population the
            // largest single movement IS the portfolio movement. Catches the largest being taken
            // over the whole book rather than over the affected subset — here 6,884.99 against a
            // portfolio total of 6,884,990.00, a thousandfold discrepancy that neither figure
            // reveals on its own.
            ImpactPreview broken = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1L,
                PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
            assertThat(broken.incoherences()).hasSize(1);
            assertThat(broken.incoherences().get(0)).contains("one contract affected");

            ImpactPreview singleContract = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1L,
                PER_CONTRACT, PER_CONTRACT, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
            assertThat(singleContract.incoherences())
                .as("one contract, and the two figures agree")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("what makes a preview structurally unusable is refused at construction")
    class Construction {

        @Test
        @DisplayName("a preview that names no version cannot be matched to one")
        void versionIdIsMandatory() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImpactPreview(
                    "  ", DRAFT, GENERATED, BOOK_AS_OF, 1_000L,
                    PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER))
                .withMessageContaining("must name the policy version it previews");
        }

        @Test
        @DisplayName("a negative contract count is refused")
        void negativeCountIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImpactPreview(
                    VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, -1L,
                    PORTFOLIO, PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER))
                .withMessageContaining("contractsAffected must be non-negative");
        }

        @Test
        @DisplayName("mixed currencies are refused, not reported as a breach")
        void mixedCurrenciesAreRefused() {
            // Thrown rather than reported because a cross-currency portfolio total is not a
            // figure anybody can reconcile — the caller has an aggregation defect, not a book
            // with a surprise in it. Money.minus takes the same position for the same reason.
            Currency usd = Currency.getInstance("USD");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImpactPreview(
                    VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1_000L,
                    PORTFOLIO, Money.of("1.00", usd), PER_CONTRACT, EIR_BEFORE, EIR_AFTER))
                .withMessageContaining("recognisedInterestDelta is USD");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImpactPreview(
                    VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1_000L,
                    PORTFOLIO, PORTFOLIO, Money.of("1.00", usd), EIR_BEFORE, EIR_AFTER))
                .withMessageContaining("largestSingleContractMovement is USD");
        }

        @Test
        @DisplayName("every reference is mandatory")
        void nullsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> new ImpactPreview(
                VERSION_ID, null, GENERATED, BOOK_AS_OF, 1L,
                PORTFOLIO, PORTFOLIO, PORTFOLIO, EIR_BEFORE, EIR_AFTER));
            assertThatNullPointerException().isThrownBy(() -> new ImpactPreview(
                VERSION_ID, DRAFT, null, BOOK_AS_OF, 1L,
                PORTFOLIO, PORTFOLIO, PORTFOLIO, EIR_BEFORE, EIR_AFTER));
            assertThatNullPointerException().isThrownBy(() -> new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1L,
                PORTFOLIO, PORTFOLIO, PORTFOLIO, EIR_BEFORE, null));
        }

        @Test
        @DisplayName("equality is by value, so a re-read preview is the same preview")
        void equalityIsByValue() {
            // Matters because the register selects and the gate compares; a preview loaded twice
            // from the same row must be one preview. Money and Rate both compare numerically
            // rather than by scale, so 6884990.00 and 6884990.000 are the same amount.
            assertThat(curveRevision()).isEqualTo(curveRevision());
            ImpactPreview rescaled = new ImpactPreview(
                VERSION_ID, DRAFT, GENERATED, BOOK_AS_OF, 1_000L,
                Money.inr("6884990.000"), PORTFOLIO, PER_CONTRACT, EIR_BEFORE, EIR_AFTER);
            assertThat(rescaled).isEqualTo(curveRevision());
        }
    }
}
