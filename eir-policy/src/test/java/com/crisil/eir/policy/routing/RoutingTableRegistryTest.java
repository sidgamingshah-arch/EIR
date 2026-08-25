package com.crisil.eir.policy.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The series of approved routing tables, and which one governs a date (FR-504, ADR-0006).
 *
 * <p>Every expected value here is derived from a stated document, never from running the
 * registry. The <em>mechanisms</em> come from the specification 6.1 mapping as it stands in
 * {@code RoutingTable.ofSpecDefaults} (benchmark movement and market credit-spread repricing
 * reset; pre-determined ESG, ratchet and step-up adjustments catch up; a negotiated change
 * runs the substantiality test) and from the reading each fixture version below states in its
 * own description. The <em>dates</em> are hand-chosen effective dates and the selection
 * expected of them is read off {@code RoutingTableVersion.isEffectiveOn}, which is inclusive
 * of the effective date. The <em>day counts</em> in the coverage assertions are counted from
 * the calendar in the comment that states them.
 *
 * <p>Three properties carry the weight of this class, and each has its own nested block:
 *
 * <ul>
 *   <li><strong>Selection is unambiguous.</strong> Exactly one table governs any date the
 *       registry covers, and which one does not depend on the order the tables were handed in.
 *   <li><strong>A gap is refused, not filled.</strong> An event date resolving to no table
 *       must fail loudly, because the alternative — borrowing the nearest reading — produces a
 *       decision stamped with a version id that version never governed, and every downstream
 *       control then agrees with the others and is wrong.
 *   <li><strong>A version change is not a code change.</strong> The Phase 2 exit gate. Same
 *       code, same driver, same instrument, two dates either side of a changeover, materially
 *       different P&amp;L — and the closed period still replays under the reading that closed it.
 * </ul>
 */
class RoutingTableRegistryTest {

    /**
     * The engine baseline, effective 2026-05-01 — the specification 6.1 reading of the April
     * 2026 IASB tentative decision. Its dates are asserted here rather than assumed, because
     * every date expectation in this class is stated relative to them.
     */
    private static final RoutingTable BASELINE = RoutingTable.currentDefault();

    private static final LocalDate BASELINE_EFFECTIVE = LocalDate.of(2026, 5, 1);

    /** The post-Exposure-Draft reading, adopted from the start of FY 2027-28. */
    private static final LocalDate ED_EFFECTIVE = LocalDate.of(2027, 4, 1);

    /** A later house-view revision, adopted from the start of FY 2028-29. */
    private static final LocalDate HOUSE_VIEW_EFFECTIVE = LocalDate.of(2028, 4, 1);

    /**
     * The H2 2026 Exposure Draft reading: an ESG margin ratchet is consideration for credit
     * risk, so it adjusts the EIR instead of producing a catch-up. One row moves; ADR-0006
     * says the driver taxonomy survives the wording change and only the mapping moves.
     */
    private static RoutingTable afterExposureDraft() {
        return BASELINE.reroute(RateDriver.ESG_LINKED, Mechanism.RESET, version(
            "RT-ED-2027.1",
            "Post-Exposure-Draft reading: an ESG margin ratchet is consideration for credit risk "
                + "and therefore adjusts the EIR.",
            ED_EFFECTIVE, LocalDate.of(2027, 3, 15)));
    }

    /**
     * A second, later revision moving a pre-determined step-up to a reset as well. Present so
     * that selection is tested over a series of three rather than a pair — a two-element
     * series cannot distinguish "the latest effective table" from "the last one added".
     */
    private static RoutingTable afterHouseViewRevision() {
        return afterExposureDraft().reroute(
            RateDriver.STEP_UP_PREDETERMINED, Mechanism.RESET, version(
                "RT-HOUSE-2028.1",
                "House view extended: a pre-determined step-up compensates for the time value of "
                    + "money over the step and therefore adjusts the EIR.",
                HOUSE_VIEW_EFFECTIVE, LocalDate.of(2028, 2, 1)));
    }

    private static RoutingTableVersion version(
        String id, String description, LocalDate effectiveFrom, LocalDate approvedOn) {
        return new RoutingTableVersion(
            id, description, effectiveFrom, "group-accounting-policy", "chief-accountant",
            approvedOn);
    }

    /** The three-version series used by most blocks below. */
    private static RoutingTableRegistry series() {
        return RoutingTableRegistry.of(BASELINE, afterExposureDraft(), afterHouseViewRevision());
    }

    @Test
    @DisplayName("the fixture dates are the ones every expectation below is stated against")
    void fixtureDatesAreWhatTheyClaim() {
        // Guards the rest of the class: if the engine baseline's effective date moved, the
        // date expectations here would silently become expectations about something else.
        assertThat(BASELINE.version().effectiveFrom())
            .as("engine baseline effective date")
            .isEqualTo(BASELINE_EFFECTIVE);
        assertThat(BASELINE.version().id()).isEqualTo("RT-BASELINE-2026.1");
        assertThat(afterExposureDraft().version().effectiveFrom()).isEqualTo(ED_EFFECTIVE);
        assertThat(afterHouseViewRevision().version().effectiveFrom())
            .isEqualTo(HOUSE_VIEW_EFFECTIVE);
    }

    @Nested
    @DisplayName("selection by date: exactly one reading governs any covered date")
    class SelectionByDate {

        @ParameterizedTest(name = "{0} is governed by {1}")
        @CsvSource({
            // The effective date itself, and the day before the successor takes over. Read off
            // RoutingTableVersion.isEffectiveOn, which is inclusive of the effective date: a
            // version governs [effectiveFrom, successor.effectiveFrom).
            "2026-05-01, RT-BASELINE-2026.1",
            "2027-03-31, RT-BASELINE-2026.1",
            "2027-04-01, RT-ED-2027.1",
            "2028-03-31, RT-ED-2027.1",
            "2028-04-01, RT-HOUSE-2028.1",
            "2099-12-31, RT-HOUSE-2028.1",
        })
        @DisplayName("the latest table whose effective date has arrived is the one in force")
        void theLatestEffectiveTableGoverns(LocalDate asOf, String expectedVersionId) {
            assertThat(series().inForceOn(asOf).version().id())
                .as("version in force on %s", asOf)
                .isEqualTo(expectedVersionId);
        }

        @Test
        @DisplayName("the changeover is a single day's difference in mechanism, not a range")
        void theChangeoverIsExact() {
            RoutingTableRegistry registry = series();

            // 2027-03-31 and 2027-04-01 are consecutive days. The ESG row differs across them
            // because the Exposure Draft version takes effect on the second of the two — this
            // is the boundary an off-by-one would move, and moving it would re-route a whole
            // day of events.
            assertThat(registry.inForceOn(LocalDate.of(2027, 3, 31))
                .mechanismFor(RateDriver.ESG_LINKED))
                .as("ESG ratchet on the last day of the baseline reading")
                .isEqualTo(Mechanism.CATCH_UP);
            assertThat(registry.inForceOn(ED_EFFECTIVE).mechanismFor(RateDriver.ESG_LINKED))
                .as("ESG ratchet on the first day of the Exposure Draft reading")
                .isEqualTo(Mechanism.RESET);
        }

        @Test
        @DisplayName("selection does not depend on the order the tables were supplied")
        void constructionOrderIsIrrelevant() {
            // A registry assembled from configuration has no reason to be in date order, and a
            // selection that depended on insertion order would be reproducible only by accident
            // — the same defect as two tables sharing an effective date, arriving by a different
            // route.
            RoutingTableRegistry reversed = RoutingTableRegistry.of(
                afterHouseViewRevision(), afterExposureDraft(), BASELINE);
            RoutingTableRegistry shuffled = RoutingTableRegistry.of(
                afterExposureDraft(), BASELINE, afterHouseViewRevision());

            for (LocalDate date : List.of(
                BASELINE_EFFECTIVE, LocalDate.of(2027, 3, 31), ED_EFFECTIVE,
                LocalDate.of(2028, 3, 31), HOUSE_VIEW_EFFECTIVE)) {
                String expected = series().inForceOn(date).version().id();
                assertThat(reversed.inForceOn(date).version().id())
                    .as("reverse-order registry on %s", date)
                    .isEqualTo(expected);
                assertThat(shuffled.inForceOn(date).version().id())
                    .as("shuffled registry on %s", date)
                    .isEqualTo(expected);
            }
            // And the series itself reads back in force order whatever it was given in.
            assertThat(reversed.versionIds()).containsExactly(
                "RT-BASELINE-2026.1", "RT-ED-2027.1", "RT-HOUSE-2028.1");
        }

        @Test
        @DisplayName("a registry of one is legal: a bank starts with one approved reading")
        void aRegistryOfOneIsLegal() {
            RoutingTableRegistry single = RoutingTableRegistry.of(BASELINE);

            assertThat(single.size()).isEqualTo(1);
            assertThat(single.earliestEffectiveDate()).isEqualTo(BASELINE_EFFECTIVE);
            assertThat(single.inForceOn(LocalDate.of(2030, 1, 1)).version().id())
                .isEqualTo("RT-BASELINE-2026.1");
        }
    }

    @Nested
    @DisplayName("gaps: an event date with no table is refused, never filled")
    class Gaps {

        @Test
        @DisplayName("a date before the earliest approved table raises, naming what was held")
        void aDateBeforeTheEarliestTableRaises() {
            // The migration case. A transition-date event dated before the bank's earliest
            // approved reading must not borrow that reading: cases 3 and 4 are the same
            // instrument in the same month, one carrying a 627.42 charge and the other nothing,
            // so a borrowed reading is not an approximation of the right answer.
            LocalDate beforeEverything = LocalDate.of(2026, 4, 30);

            assertThatExceptionOfType(RoutingTableUnavailableException.class)
                .isThrownBy(() -> series().inForceOn(beforeEverything))
                .withMessageContaining("no approved routing table is in force on 2026-04-30")
                .withMessageContaining("the earliest table takes effect 2026-05-01")
                .withMessageContaining("a version id to an event that version never governed")
                .withMessageContaining("RT-BASELINE-2026.1");
        }

        @Test
        @DisplayName("the unroutable date is carried as data, not only inside the message")
        void theExceptionCarriesTheEventDate() {
            // The caller this refusal anticipates writes an exception-queue row per contract.
            // Recovering the date by parsing the message would make the message a wire format.
            RoutingTableUnavailableException raised = catchThrowableOfType(
                RoutingTableUnavailableException.class,
                () -> series().inForceOn(LocalDate.of(2026, 4, 30)));

            assertThat(raised.eventDate()).contains(LocalDate.of(2026, 4, 30));
            assertThat(raised.versionId()).isEmpty();
        }

        @Test
        @DisplayName("the non-throwing form reports the absence instead of guessing")
        void findInForceOnReportsAbsence() {
            // For a caller with somewhere to put "no table" — an exception-queue entry per
            // contract rather than the first failure as a stack trace.
            assertThat(series().findInForceOn(LocalDate.of(2026, 4, 30))).isEmpty();
            assertThat(series().findInForceOn(BASELINE_EFFECTIVE)).isPresent();
        }

        @Test
        @DisplayName("no interior gap exists: every day from the first table onwards resolves")
        void thereIsNoInteriorGap() {
            // A version carries an effective-from and no expiry, so consecutive tables cannot
            // fail to meet — the gap class that would otherwise need a continuity check is
            // unrepresentable. Walked day by day across both changeovers rather than asserted,
            // because that is the claim: every single date resolves.
            RoutingTableRegistry registry = series();
            List<LocalDate> unresolved = new ArrayList<>();
            for (LocalDate date = BASELINE_EFFECTIVE;
                !date.isAfter(HOUSE_VIEW_EFFECTIVE.plusDays(1));
                date = date.plusDays(1)) {
                if (registry.findInForceOn(date).isEmpty()) {
                    unresolved.add(date);
                }
            }
            assertThat(unresolved)
                .as("dates between the first effective date and the last changeover with no table")
                .isEmpty();
        }

        @Test
        @DisplayName("pre-close coverage is complete when the window starts inside the series")
        void coveragePassesInsideTheSeries() {
            // FY 2027-28: 2027-04-01 to 2028-03-31. The Exposure Draft version takes effect on
            // the first day of it and the house-view version on 2028-04-01, the day after the
            // last — so exactly one reading governs the year.
            RoutingCoverage coverage = series().coverageOver(
                LocalDate.of(2027, 4, 1), LocalDate.of(2028, 3, 31));

            assertThat(coverage.isComplete()).isTrue();
            assertThat(coverage.uncoveredDays()).isZero();
            assertThat(coverage.firstUncovered()).isNull();
            assertThat(coverage.governingVersionIds()).containsExactly("RT-ED-2027.1");
            assertThat(coverage.spansAChangeover()).isFalse();
            assertThat(coverage.detail()).contains("in force throughout").contains("RT-ED-2027.1");
        }

        @Test
        @DisplayName("a covered window spanning a changeover names every reading that governed it")
        void coverageNamesEveryGoverningVersion() {
            // 2026-06-01 to 2028-06-30 spans both changeovers, so all three readings govern part
            // of it. Naming only the first would tell an auditor that one reading governed the
            // period — and the changeover is the materially significant fact, since the readings
            // differ by a 627.42 charge on reference cases 3 and 4.
            RoutingCoverage coverage = series().coverageOver(
                LocalDate.of(2026, 6, 1), LocalDate.of(2028, 6, 30));

            assertThat(coverage.isComplete()).isTrue();
            assertThat(coverage.governingVersionIds()).containsExactly(
                "RT-BASELINE-2026.1", "RT-ED-2027.1", "RT-HOUSE-2028.1");
            assertThat(coverage.spansAChangeover()).isTrue();
            assertThat(coverage.detail()).contains("in force throughout");
        }

        @Test
        @DisplayName("a version superseded before the window begins is not named as governing it")
        void coverageExcludesVersionsThatEndedBeforeTheWindow() {
            // 2028-04-01 onwards: the baseline was superseded 2027-04-01 and the Exposure Draft
            // reading 2028-04-01, the window's own first day. Only the house-view reading governs
            // any part of it. A version list that included the others would overstate the
            // policies a period was computed under.
            RoutingCoverage coverage = series().coverageOver(
                LocalDate.of(2028, 4, 1), LocalDate.of(2029, 3, 31));

            assertThat(coverage.governingVersionIds()).containsExactly("RT-HOUSE-2028.1");
        }

        @Test
        @DisplayName("pre-close coverage reports the uncovered day count as the deviation")
        void coverageFailsBeforeTheSeriesBegins() {
            // April 2026 has 30 days and the earliest approved table takes effect 2026-05-01,
            // so every day of the window is uncovered: 30. Counted from the calendar, not from
            // the registry.
            RoutingCoverage coverage = series().coverageOver(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

            assertThat(coverage.isComplete()).isFalse();
            assertThat(coverage.uncoveredDays()).isEqualTo(30);
            assertThat(coverage.firstUncovered()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(coverage.lastUncovered()).isEqualTo(LocalDate.of(2026, 4, 30));
            // No version governs any part of a window that ends before the series begins.
            assertThat(coverage.governingVersionIds()).isEmpty();
            assertThat(coverage.detail())
                .contains("no approved routing table for 30 day(s) from 2026-04-01 to 2026-04-30");
        }

        @Test
        @DisplayName("a window straddling the first effective date reports only the days it lacks")
        void coverageClipsToTheGapItself() {
            // 2026-04-29 to 2026-05-31 straddles the baseline's 2026-05-01 start. Uncovered:
            // 29 and 30 April — two days. The remaining 31 days are governed by the baseline, so
            // reporting the whole window would overstate the gap and send someone looking for a
            // configuration problem in May that does not exist.
            RoutingCoverage coverage = series().coverageOver(
                LocalDate.of(2026, 4, 29), LocalDate.of(2026, 5, 31));

            assertThat(coverage.uncoveredDays()).isEqualTo(2);
            assertThat(coverage.lastUncovered()).isEqualTo(LocalDate.of(2026, 4, 30));
            assertThat(coverage.governingVersionIds()).containsExactly("RT-BASELINE-2026.1");
        }

        @Test
        @DisplayName("a single uncovered day is one day, not zero")
        void coverageCountsInclusively() {
            // The day before the baseline takes effect, as both ends of the window. Inclusive
            // counting: an off-by-one here would report a real gap as no gap.
            RoutingCoverage coverage = series().coverageOver(
                LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 30));

            assertThat(coverage.isComplete()).isFalse();
            assertThat(coverage.uncoveredDays()).isEqualTo(1);
            assertThat(coverage.firstUncovered()).isEqualTo(coverage.lastUncovered());
        }

        @Test
        @DisplayName("an inverted window is a caller defect, not a coverage finding")
        void anInvertedWindowIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> series().coverageOver(
                    LocalDate.of(2027, 4, 1), LocalDate.of(2027, 3, 31)))
                .withMessageContaining("ends before it begins");
        }

        @Test
        @DisplayName("a coverage answer whose gap contradicts itself cannot be constructed")
        void anIncoherentCoverageAnswerIsRejected() {
            // The record is public and the registry is not its only possible producer. An
            // unchecked one could publish "no approved routing table for 5 day(s) from
            // 2027-06-30 to 2027-06-01" — a control report nobody can action, which is the
            // failure the checks exist to prevent.
            LocalDate from = LocalDate.of(2027, 6, 1);
            LocalDate to = LocalDate.of(2027, 6, 30);

            assertThatIllegalArgumentException()
                .as("a gap running backwards")
                .isThrownBy(() -> new RoutingCoverage(
                    from, to, 5, LocalDate.of(2027, 6, 30), LocalDate.of(2027, 6, 26), List.of()))
                .withMessageContaining("ends before it begins");
            assertThatIllegalArgumentException()
                .as("a gap outside the window it is reported against")
                .isThrownBy(() -> new RoutingCoverage(
                    from, to, 2, LocalDate.of(2027, 5, 30), LocalDate.of(2027, 5, 31), List.of()))
                .withMessageContaining("falls outside the window");
            assertThatIllegalArgumentException()
                .as("a day count that is not the span of the gap: 1 to 5 June is 5 days, not 4")
                .isThrownBy(() -> new RoutingCoverage(
                    from, to, 4, from, LocalDate.of(2027, 6, 5), List.of()))
                .withMessageContaining("which spans 5 day(s) inclusive");
            assertThatIllegalArgumentException()
                .as("a size with no location")
                .isThrownBy(() -> new RoutingCoverage(from, to, 3, null, null, List.of()))
                .withMessageContaining("disagrees with a first uncovered date");
        }
    }

    @Nested
    @DisplayName("overlap and identity: rejected at construction, not resolved at lookup")
    class OverlapAndIdentity {

        @Test
        @DisplayName("two tables effective on the same date are rejected as ambiguous")
        void sameEffectiveDateIsRejected() {
            // Two readings in force on one date: "which governs" has two answers, and whichever
            // a lookup returned would depend on sort stability rather than on an approval.
            RoutingTable rival = BASELINE.reroute(
                RateDriver.CREDIT_RATCHET_PREDETERMINED, Mechanism.RESET, version(
                    "RT-RIVAL-2027.1", "A second reading, approved for the same start date.",
                    ED_EFFECTIVE, LocalDate.of(2027, 3, 20)));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> RoutingTableRegistry.of(BASELINE, afterExposureDraft(), rival))
                .withMessageContaining("are both effective 2027-04-01")
                .withMessageContaining("ambiguous")
                .withMessageContaining("needs its own effective date");
        }

        @Test
        @DisplayName("two mappings claiming one version id are rejected: replay could not tell them apart")
        void duplicateVersionIdIsRejected() {
            // Constructed directly rather than through reroute, because reroute already refuses
            // to reuse the id it was called on — this is the path around it, where two
            // independently-built tables are collected into one series.
            RoutingTable impostor = RoutingTable.ofSpecDefaults(version(
                "RT-ED-2027.1", "A different mapping under an id that is already in use.",
                LocalDate.of(2027, 7, 1), LocalDate.of(2027, 6, 1)));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> RoutingTableRegistry.of(afterExposureDraft(), impostor))
                .withMessageContaining("appears twice")
                .withMessageContaining("RT-ED-2027.1")
                .withMessageContaining("indistinguishable on replay");
        }

        @Test
        @DisplayName("the same table supplied twice trips the effective-date check first")
        void theSameTableTwiceIsRejected() {
            // Two copies share an effective date as well as an id, so the ambiguity check is the
            // one that fires. Asserted on the message that actually appears rather than on the id
            // alone: an assertion satisfied by either check would still pass with the identity
            // check deleted, and duplicateVersionIdIsRejected is what covers that path.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> RoutingTableRegistry.of(BASELINE, BASELINE))
                .withMessageContaining("'RT-BASELINE-2026.1' and 'RT-BASELINE-2026.1'")
                .withMessageContaining("are both effective 2026-05-01");
        }

        @Test
        @DisplayName("an empty registry is refused at wiring time, not on the first event")
        void anEmptyRegistryIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> RoutingTableRegistry.of(List.of()))
                .withMessageContaining("cannot route anything")
                .withMessageContaining("FR-504");
        }
    }

    @Nested
    @DisplayName("the exit gate: a change of reading is a version, not a deploy")
    class VersionChangeWithoutDeploy {

        @Test
        @DisplayName("the same event on two dates routes to different mechanisms, same code")
        void aStandardsChangeIsSelectedByDate() {
            RoutingTableRegistry registry = series();

            // An ESG-linked margin ratchet on a floating-rate loan. Before the Exposure Draft
            // reading takes effect it is a pre-determined adjustment compensating for neither
            // the time value of money nor credit risk, so it falls outside B5.4.5 and produces a
            // B5.4.6 catch-up. Under the post-ED reading it is consideration for credit risk and
            // adjusts the EIR. Both readings are derived from the fixture descriptions above,
            // not from the registry.
            RoutingDecision before = registry.route(
                RateDriver.ESG_LINKED, RateType.FLOATING, LocalDate.of(2027, 3, 31));
            RoutingDecision after = registry.route(
                RateDriver.ESG_LINKED, RateType.FLOATING, ED_EFFECTIVE);

            assertThat(before.mechanism()).isEqualTo(Mechanism.CATCH_UP);
            assertThat(after.mechanism()).isEqualTo(Mechanism.RESET);
            // Materially different P&L: a catch-up recognises the restatement immediately, a
            // reset recognises nothing and only changes the rate going forward.
            assertThat(before.resolvesRate()).isFalse();
            assertThat(after.resolvesRate()).isTrue();
            // And each decision names the version that produced it, which is the field the
            // event persists.
            assertThat(before.routingTableVersionId()).isEqualTo("RT-BASELINE-2026.1");
            assertThat(after.routingTableVersionId()).isEqualTo("RT-ED-2027.1");
        }

        @Test
        @DisplayName("appending an approved table changes tomorrow's routing and nothing else")
        void withAppendsWithoutDisturbingWhatIsClosed() {
            RoutingTableRegistry beforeAdoption = RoutingTableRegistry.of(BASELINE);
            RoutingTableRegistry afterAdoption = beforeAdoption.with(afterExposureDraft());

            // Under the one-version registry every date routes an ESG ratchet to a catch-up,
            // including dates after the Exposure Draft version's effective date — because that
            // version does not exist there. That is the whole content of "changed without a
            // deploy": the code is identical across these two registries.
            assertThat(beforeAdoption.route(RateDriver.ESG_LINKED, RateType.FLOATING, ED_EFFECTIVE)
                .mechanism()).isEqualTo(Mechanism.CATCH_UP);
            assertThat(afterAdoption.route(RateDriver.ESG_LINKED, RateType.FLOATING, ED_EFFECTIVE)
                .mechanism()).isEqualTo(Mechanism.RESET);

            // Dates before the new version's effective date are untouched by the adoption.
            LocalDate dayBefore = ED_EFFECTIVE.minusDays(1);
            assertThat(afterAdoption.route(RateDriver.ESG_LINKED, RateType.FLOATING, dayBefore)
                .routingTableVersionId())
                .isEqualTo(beforeAdoption.route(RateDriver.ESG_LINKED, RateType.FLOATING, dayBefore)
                    .routingTableVersionId());

            // And the registry that was handed to an in-flight close run did not acquire the new
            // reading behind its back.
            assertThat(beforeAdoption.size()).isEqualTo(1);
            assertThat(afterAdoption.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("a version cannot be inserted behind one already held")
        void withRefusesAMidSeriesInsertion() {
            // Effective 2026-09-01, slid behind the 2027 and 2028 readings. Nothing in the type
            // system objects: the version is not retrospective in its own right — approved
            // 2026-08-01, effective 2026-09-01 — because isRetrospective compares a version's
            // own two dates and knows nothing about what it was inserted behind. The consequence
            // is that a December 2026 event, recomputed by date, would change mechanism in a
            // closed period. Amending history is a restatement decision, not an adoption.
            RoutingTable insertion = BASELINE.reroute(
                RateDriver.ESG_LINKED, Mechanism.RESET, version(
                    "RT-MID-2026.2", "A reading slid in behind two later ones.",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1)));

            assertThat(insertion.version().isRetrospective())
                .as("the inserted version does not look retrospective on its own dates")
                .isFalse();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> series().with(insertion))
                .withMessageContaining("takes effect 2026-09-01")
                .withMessageContaining("on or before the latest version already held")
                .withMessageContaining("already-closed period");

            // The initial load is a different act: of(...) defines a series rather than amending
            // one, so it accepts the same tables in any order.
            assertThat(RoutingTableRegistry.of(
                BASELINE, insertion, afterExposureDraft(), afterHouseViewRevision()).versionIds())
                .containsExactly("RT-BASELINE-2026.1", "RT-MID-2026.2", "RT-ED-2027.1",
                    "RT-HOUSE-2028.1");
        }

        @Test
        @DisplayName("a table effective on the same day as the latest held one is not an append")
        void withRefusesASameDayAdoption() {
            RoutingTable sameDay = BASELINE.reroute(
                RateDriver.NEGOTIATED, Mechanism.CATCH_UP, version(
                    "RT-SAME-DAY-2028.1", "A second reading for a date already governed.",
                    HOUSE_VIEW_EFFECTIVE, LocalDate.of(2028, 3, 1)));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> series().with(sameDay))
                .withMessageContaining("on or before the latest version already held");
        }

        @Test
        @DisplayName("only the changed row moves: a version is a delta, not a rebuild")
        void onlyTheChangedRowMoves() {
            RoutingTable baselineTable = series().inForceOn(LocalDate.of(2027, 3, 31));
            RoutingTable edTable = series().inForceOn(ED_EFFECTIVE);

            for (RateDriver driver : RateDriver.values()) {
                if (driver == RateDriver.ESG_LINKED) {
                    continue;
                }
                assertThat(edTable.mechanismFor(driver))
                    .as("driver %s is untouched by the ESG re-routing", driver)
                    .isEqualTo(baselineTable.mechanismFor(driver));
            }
        }
    }

    @Nested
    @DisplayName("replay: the recorded version, never the calendar")
    class Replay {

        @Test
        @DisplayName("a closed period replays under the reading that closed it")
        void replayUsesTheRecordedVersion() {
            RoutingTableRegistry registry = series();

            // An event routed in FY 2026-27, recorded with the baseline version id. Two later
            // readings have since been approved. Replaying by the recorded id still gives the
            // catch-up the period was closed on; replaying by today's date would give a reset,
            // which is the defect ADR-0006 exists to prevent — and DT-1 would either fail or,
            // worse, pass while being wrong.
            RoutingDecision original = registry.route(
                RateDriver.ESG_LINKED, RateType.FLOATING, LocalDate.of(2026, 12, 31));
            assertThat(original.routingTableVersionId()).isEqualTo("RT-BASELINE-2026.1");

            RoutingDecision replayed = registry.replay(
                RateDriver.ESG_LINKED, RateType.FLOATING, original.routingTableVersionId());

            assertThat(replayed).isEqualTo(original);
            assertThat(replayed.mechanism()).isEqualTo(Mechanism.CATCH_UP);
            // The contrast that makes the assertion mean something: the current reading differs.
            assertThat(registry.route(RateDriver.ESG_LINKED, RateType.FLOATING, HOUSE_VIEW_EFFECTIVE)
                .mechanism()).isEqualTo(Mechanism.RESET);
        }

        @Test
        @DisplayName("a version this registry does not hold cannot be replayed by date instead")
        void anUnknownVersionIsRefused() {
            assertThatExceptionOfType(RoutingTableUnavailableException.class)
                .isThrownBy(() -> series().replay(
                    RateDriver.ESG_LINKED, RateType.FLOATING, "RT-FROM-ANOTHER-DEPLOYMENT"))
                .withMessageContaining("RT-FROM-ANOTHER-DEPLOYMENT")
                .withMessageContaining("cannot be replayed under the reading that routed it")
                .withMessageContaining("RT-HOUSE-2028.1");
        }

        @Test
        @DisplayName("the unresolvable version id is carried as data, and is the key searched")
        void theExceptionCarriesTheVersionId() {
            // Padded on purpose. The lookup strips before searching, so the message must quote
            // the stripped form — a diagnostic naming a value that differs from the key actually
            // searched sends the reader looking for the wrong thing.
            RoutingTableUnavailableException raised = catchThrowableOfType(
                RoutingTableUnavailableException.class,
                () -> series().replay(
                    RateDriver.ESG_LINKED, RateType.FLOATING, "  RT-FROM-ANOTHER-DEPLOYMENT  "));

            assertThat(raised.versionId()).contains("RT-FROM-ANOTHER-DEPLOYMENT");
            assertThat(raised.eventDate()).isEmpty();
            assertThat(raised).hasMessageContaining("'RT-FROM-ANOTHER-DEPLOYMENT'");
        }

        @Test
        @DisplayName("every held version is addressable by id, in force order")
        void versionsAreAddressableById() {
            RoutingTableRegistry registry = series();

            assertThat(registry.versionIds()).containsExactly(
                "RT-BASELINE-2026.1", "RT-ED-2027.1", "RT-HOUSE-2028.1");
            for (String id : registry.versionIds()) {
                assertThat(registry.version(id).version().id()).isEqualTo(id);
            }
            assertThat(registry.findVersion("RT-NOT-APPROVED")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the one rule that stays in code survives every version")
    class RateTypeOverride {

        @ParameterizedTest(name = "FIXED + {0} is a modification under every version in the series")
        @EnumSource(value = RateDriver.class, names = {"TIME_VALUE_OF_MONEY", "CREDIT_RISK_MARKET"})
        @DisplayName("FR-507: a market movement on a fixed-rate instrument is a modification")
        void theOverrideSurvivesSelectionByDate(RateDriver driver) {
            RoutingTableRegistry registry = series();

            // B5.4.5 covers instruments that reprice off a benchmark by their own terms. A
            // fixed-rate instrument has no such term, so this combination can only have arisen
            // from renegotiation — it is a modification and runs the substantiality assessment.
            // The premise is the instrument's terms, not any reading of B5.4.5, so no approved
            // table version can turn it off. Checked on a date governed by each of the three.
            for (LocalDate date : List.of(
                BASELINE_EFFECTIVE, ED_EFFECTIVE, HOUSE_VIEW_EFFECTIVE)) {
                RoutingDecision decision = registry.route(driver, RateType.FIXED, date);
                assertThat(decision.mechanism())
                    .as("FIXED + %s on %s", driver, date)
                    .isEqualTo(Mechanism.MODIFICATION_TEST);
                assertThat(decision.overriddenByRateTypeCheck()).isTrue();
                // The table row is still recorded as overridden rather than dressed up as the
                // table's own answer: the auditor's question is why.
                assertThat(decision.rationale()).contains("overriding routing table");
                assertThat(decision.rationale())
                    .contains(registry.inForceOn(date).version().id());
            }
        }

        @Test
        @DisplayName("a table version that maps a market movement to a catch-up cannot defeat it")
        void theOverrideBeatsAContraryTableRow() {
            // A permissible different house view: benchmark movement treated as a revision of
            // estimated cash flows. On a FLOATING instrument the row stands; on a FIXED one the
            // rate-type check still fires, because the instrument has no repricing term for the
            // row to apply to. If this row could defeat the check, a future table version would
            // quietly route a renegotiated fixed-rate loan to a catch-up and the modification
            // question — substantiality, derecognition — would leave the ledger with nobody
            // declining it.
            RoutingTable contrary = BASELINE.reroute(
                RateDriver.TIME_VALUE_OF_MONEY, Mechanism.CATCH_UP, version(
                    "RT-CONTRARY-2029.1",
                    "House view: benchmark movement is a revision of estimated cash flows.",
                    LocalDate.of(2029, 4, 1), LocalDate.of(2029, 3, 1)));
            RoutingTableRegistry registry = series().with(contrary);
            LocalDate underContrary = LocalDate.of(2029, 4, 1);

            assertThat(registry.route(
                RateDriver.TIME_VALUE_OF_MONEY, RateType.FLOATING, underContrary).mechanism())
                .as("the table row governs a floating-rate instrument")
                .isEqualTo(Mechanism.CATCH_UP);
            assertThat(registry.route(
                RateDriver.TIME_VALUE_OF_MONEY, RateType.FIXED, underContrary).mechanism())
                .as("the rate-type check governs a fixed-rate instrument, whatever the row says")
                .isEqualTo(Mechanism.MODIFICATION_TEST);
        }

        @ParameterizedTest(name = "FLOATING + {0} keeps the selected table's row")
        @EnumSource(value = RateDriver.class, names = {"TIME_VALUE_OF_MONEY", "CREDIT_RISK_MARKET"})
        @DisplayName("the override never fires on a floating instrument doing what its terms provide")
        void theOverrideDoesNotFireOnFloating(RateDriver driver) {
            // The half of the pair that makes the previous assertions mean something. Under the
            // specification 6.1 mapping both of these drivers reset on a floating instrument.
            RoutingDecision decision = series().route(driver, RateType.FLOATING, ED_EFFECTIVE);

            assertThat(decision.mechanism()).isEqualTo(Mechanism.RESET);
            assertThat(decision.overriddenByRateTypeCheck()).isFalse();
        }
    }

    @Nested
    @DisplayName("immutability and the audit sentence")
    class ImmutabilityAndAudit {

        @Test
        @DisplayName("the series cannot be mutated through the accessor or the supplied list")
        void theSeriesIsCopied() {
            List<RoutingTable> supplied = new ArrayList<>(List.of(BASELINE, afterExposureDraft()));
            RoutingTableRegistry registry = RoutingTableRegistry.of(supplied);

            supplied.clear();
            assertThat(registry.size())
                .as("clearing the supplied list must not empty the registry")
                .isEqualTo(2);
            assertThat(registry.tables()).isUnmodifiable();
            assertThat(registry.versionIds()).isUnmodifiable();
        }

        @Test
        @DisplayName("describe names each reading, its window and its approver")
        void describeNamesTheChangeovers() {
            String description = series().describe();

            // The windows are inclusive of the last day the version governs: the baseline runs
            // to 2027-03-31, the day before the Exposure Draft reading takes effect. An exclusive
            // end date reads as an off-by-one to anyone reconciling a changeover-date event.
            assertThat(description).contains("3 approved version(s)");
            assertThat(description)
                .contains("RT-BASELINE-2026.1 in force 2026-05-01 until 2027-03-31");
            assertThat(description).contains("RT-ED-2027.1 in force 2027-04-01 until 2028-03-31");
            assertThat(description).contains("RT-HOUSE-2028.1 in force 2028-04-01 onwards");
            assertThat(description).contains("approved by chief-accountant");
        }

        @Test
        @DisplayName("a retrospective version is flagged in the audit sentence, not rejected")
        void retrospectiveVersionsAreFlagged() {
            // Approved 2029-06-01 for an effective date of 2029-04-01. Permitted — a correction
            // adopted part-way through a year legitimately takes effect from its start — but it
            // restates figures somebody has already reported, so it must never pass unremarked.
            // Events already routed keep the version id they recorded, so it can only ever be the
            // basis of an explicit restatement, never a silent re-route.
            RoutingTable retrospective = BASELINE.reroute(
                RateDriver.BEHAVIOURAL_ESTIMATE, Mechanism.RESET, version(
                    "RT-RESTATE-2029.1", "Correction adopted from the start of the year.",
                    LocalDate.of(2029, 4, 1), LocalDate.of(2029, 6, 1)));

            RoutingTableRegistry registry = series().with(retrospective);

            assertThat(retrospective.version().isRetrospective()).isTrue();
            assertThat(registry.describe()).contains("RT-RESTATE-2029.1");
            // The baseline is not retrospective — approved 2026-04-30, effective 2026-05-01 — and
            // neither are the other two fixtures, so exactly one line carries the flag. Asserted
            // as "only once" because a flag that is always on tells a reader nothing.
            assertThat(BASELINE.version().isRetrospective()).isFalse();
            assertThat(registry.describe()).containsOnlyOnce("(RETROSPECTIVE)");
        }
    }
}
