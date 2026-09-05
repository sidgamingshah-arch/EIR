package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.run.ContractComputation;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.batch.PartitionKey;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The five latent defects {@code docs/08} records against this module, each driven against a live
 * cluster on data that trips it.
 *
 * <h2>What "latent" meant, and what this changes</h2>
 *
 * <p>All five were found by reviewing the module after its first successful build, and all five were
 * recorded as latent because nothing constructed {@code JdbcPorts} for a run. Wiring
 * {@code eir-batch} to the ports removed that excuse, and the first close through them still tripped
 * none of the five — because the shared fixture is arranged so it cannot: its cashflow line is dated
 * the 30th so the calendar-month window catches it, every stored solve carries
 * {@code PERIODIC_INDEX}, there is no weekly contract, and there is one book.
 *
 * <p>So the defects were reachable and unexercised, which is the worst of the three states: worse
 * than unreachable, because a deployment reaches them, and worse than exercised, because nothing
 * says what they do. This class says what they do. A test asserting a defect is a record, not an
 * endorsement, so as each is fixed its tests here are <b>inverted</b> rather than deleted: the
 * class keeps the finding, the argument and the input that trips it, and flips what it requires of
 * the code. Section 1 is the first to have been through that, and its comment block carries what it
 * used to assert — which turned out to be a true observation of two ports pointed at a wrong
 * conclusion, and worth keeping for that reason alone. Sections 2 to 5 still pin CURRENT behaviour
 * and must fail when their defects are fixed.
 *
 * <p>{@link LatentDefectFixture} explains why the data lives in a second database: one of the five is
 * a missing book predicate, and seeding a second book into the shared fixture would turn every other
 * live test red for a defect already recorded.
 */
@Tag("live-db")
class LatentDefectsLiveTest {

    private static DataSource dataSource;
    private static JdbcPorts main;

    private static final AsAtBoundary NOW =
        AsAtBoundary.live(Fixtures.PERIOD_END, Fixtures.AS_AT_CORRECTED);

    @BeforeAll
    static void seed() {
        dataSource = LatentDefectDatabase.dataSource();
        LatentDefectFixture.seed(dataSource);
        main = new JdbcPorts(dataSource, Fixtures.BOOK_ID);
    }

    @Nested
    @DisplayName("1. RC-1's engine leg no longer comes from the CBS row (FIXED)")
    class ReconciliationAgainstItself {

        // ============================================================ what this class used to say
        //
        // These two tests were written to RECORD a defect, and it has since been fixed, so they now
        // record the fix instead. What they asserted before:
        //
        //   bothLegsComeFromOneRow    -- that OpeningState.contractualInterestBilled and
        //                                CoreBankingFeed.billedInterest are the same figure from the
        //                                same cbs_billed_interest row.
        //   movingTheRowMovesBothLegs -- that a single UPDATE of that row moved BOTH of RC-1's legs
        //                                together, so no value of the column could produce a break.
        //
        // The first assertion was TRUE and was never the defect. Both ports carry the CBS figure by
        // design: OpeningState documents the field as "what the borrower was billed, from the CBS",
        // and a feed reading the CBS is a feed doing its job. Reading one row twice is only a
        // problem if somebody treats one of the reads as the ENGINE's answer.
        //
        // Which is exactly what happened. EirService.contractualLegLines built RC-1's engine leg
        // from OpeningState.contractualInterestBilled, because nothing in the month-end run computed
        // a contractual accrual and that field was the only interest figure in reach. So the defect
        // was one line in eir-api, not anything in this module -- and the second test above was a
        // true observation of a port pointed at the wrong conclusion.
        //
        // ContractPipeline now derives the contractual leg itself, from openingContractual at the
        // contractual rate, and EirService consumes that. On this module's path openingContractual
        // comes from readPriorClosing -- the prior period's own closing_contractual column -- which
        // is a different column in a different table from cbs_billed_interest. That is what the two
        // tests below prove, and proving it here matters because the in-memory book cannot: its
        // Seed holds both figures as constants, so only a real schema can show the two sides are
        // genuinely separately sourced.

        @Test
        @DisplayName("the two CBS-sourced ports still agree, and that is by design")
        void bothLegsComeFromOneRow() {
            List<CbsBilledInterest> feed = main.coreBanking().billedInterest(NOW);
            ContractStateSource.OpeningState state =
                main.contractState().openingState(Fixtures.CONTRACT_ID, NOW).orElseThrow();

            BigDecimal cbsLeg = feed.stream()
                .filter(row -> row.contractId().equals(Fixtures.CONTRACT_ID))
                .findFirst().orElseThrow()
                .billedInterest().amount();

            assertThat(state.contractualInterestBilled().amount())
                .as("both ports report what the borrower was billed, so they MUST agree; a"
                    + " disagreement here would mean two reads of one column under one"
                    + " system-time predicate returned different rows")
                .isEqualByComparingTo(cbsLeg);
        }

        @Test
        @DisplayName("moving the CBS row leaves the balance the engine accrues on untouched")
        void movingTheRowMovesBothLegs() throws Exception {
            // The decisive test, inverted. It used to require that both legs followed the UPDATE.
            // It now requires that the balance RC-1's ENGINE leg is derived from does not.
            //
            // openingContractual is the whole engine side on this path: ContractPipeline computes
            // openingContractual x ((1+contractual rate)^n - 1). If it moved with an UPDATE of
            // cbs_billed_interest, the two sides of RC-1 would still be one figure however
            // eir-api were wired, and this module would be re-introducing the defect underneath
            // the fix. Restored afterwards so test order cannot matter.
            BigDecimal accrualBasisBefore = openingContractual();
            BigDecimal cbsBefore = cbsLeg();
            assertThat(accrualBasisBefore)
                .as("the fixture must not happen to carry equal figures, or this test could pass"
                    + " on a path that did conflate them")
                .isNotEqualByComparingTo(cbsBefore);

            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.executeUpdate(
                    "UPDATE cbs_billed_interest SET billed_interest = '99999.990000'"
                        + " WHERE contract_id = '" + Fixtures.CONTRACT_ID + "'"
                        + " AND period_id = " + Fixtures.PERIOD_ID);
            }
            try {
                assertThat(cbsLeg())
                    .as("the CBS leg follows the CBS row, which is the point of the feed")
                    .isEqualByComparingTo(new BigDecimal("99999.990000"));
                assertThat(openingContractual())
                    .as("and the engine's accrual basis does NOT: it is the prior period's"
                        + " closing_contractual, so the two sides of RC-1 are two sources and the"
                        + " control can report a break")
                    .isEqualByComparingTo(accrualBasisBefore);
            } finally {
                try (var connection = dataSource.getConnection();
                     var statement = connection.createStatement()) {
                    statement.executeUpdate(
                        "UPDATE cbs_billed_interest SET billed_interest = '" + cbsBefore + "'"
                            + " WHERE contract_id = '" + Fixtures.CONTRACT_ID + "'"
                            + " AND period_id = " + Fixtures.PERIOD_ID);
                }
            }
        }

        private BigDecimal openingContractual() {
            return main.contractState().openingState(Fixtures.CONTRACT_ID, NOW).orElseThrow()
                .openingContractual().amount();
        }

        private BigDecimal cbsLeg() {
            return main.coreBanking().billedInterest(NOW).stream()
                .filter(row -> row.contractId().equals(Fixtures.CONTRACT_ID))
                .findFirst().orElseThrow()
                .billedInterest().amount();
        }
    }

    @Nested
    @DisplayName("2. the population query is scoped to its book (FIXED)")
    class PopulationIgnoresTheBook {

        // ============================================================ what this class used to say
        //
        // mainEnumeratesTheOtherBook required that a MAIN run's population CONTAINED the IGAAP
        // contract, and theOtherBookEnumeratesMain required the same in reverse -- "unscoped in
        // both directions, so neither book's run is a run of that book". SELECT_POPULATION now
        // carries AND c.book_id = ?, so both are inverted. The fixture and the argument are kept
        // exactly as they were, because they are what makes the fix checkable: two books, one
        // facility each, and a run of one that must see one.

        @Test
        @DisplayName("a run of book MAIN enumerates MAIN's contracts and not IGAAP's")
        void mainEnumeratesTheOtherBook() {
            // FR-109's parallel books: the same facility measured on the ACPIR basis, on IGAAP and
            // on a tax basis. V1's contract_source_ref_uq is (source_system_ref, entity_id, book_id)
            // precisely so one facility can appear once per book -- and JdbcContractSource held a
            // bookId, stored it, and did not put it in SELECT_POPULATION.
            List<String> population = main.contracts().contractIdsInScope(NOW);

            assertThat(population)
                .as("a MAIN run must not reach into another book; every balance read is scoped by"
                    + " book, and a population that is not produces contracts with no balances")
                .doesNotContain(LatentDefectFixture.OTHER_BOOK_ID);
            assertThat(population)
                .as("and it must still contain its own, or the predicate is scoping to nothing --"
                    + " an empty population reconciles perfectly and is the failure this half of"
                    + " the assertion exists to catch")
                .contains(Fixtures.CONTRACT_ID);

            // The consequence that has gone with it, and it was worse than the over-enumeration:
            // the other book's contract has no MAIN-book balance, so FR-905 quarantined it with a
            // message about a missing opening state rather than about a book. An operator read
            // "no state recorded" for a contract that is fully recorded in the book it belongs to.
            // The state is still absent -- that part was never wrong -- but nothing now asks.
            assertThat(main.contractState().openingState(LatentDefectFixture.OTHER_BOOK_ID, NOW))
                .as("still no MAIN-book state for it, which is correct; the fix is that the run no"
                    + " longer enumerates it and so no longer quarantines it for the wrong reason")
                .isEmpty();
        }

        @Test
        @DisplayName("a run of book IGAAP sees only IGAAP's contract, symmetrically")
        void theOtherBookEnumeratesMain() {
            JdbcPorts igaap = new JdbcPorts(dataSource, LatentDefectFixture.OTHER_BOOK);

            assertThat(igaap.contracts().contractIdsInScope(NOW))
                .as("scoped in both directions, so each book's run is a run of that book; asserted"
                    + " from the other side because a predicate bound to a constant rather than to"
                    + " bookId() would pass the MAIN test alone")
                .contains(LatentDefectFixture.OTHER_BOOK_ID)
                .doesNotContain(Fixtures.CONTRACT_ID);
        }
    }

    @Nested
    @DisplayName("3. the stored rate is wrapped AT its convention (FIXED)")
    class RateWrappedWithoutItsConvention {

        // ============================================================ what this class used to say
        //
        // theConventionIsIgnored required that an ACTUAL_DATE solve on a MONTHLY schedule yielded
        // a rate at 12 periods a year -- "wrapped at the SCHEDULE's frequency, with the stored
        // ACTUAL_DATE convention unread" -- and that the port boundary refused nothing, so the
        // contradicting pair travelled into the roll-forward. Both are now inverted. The fixture's
        // ACTUAL_DATE contract is unchanged; only what the adapter does with it has.

        @Test
        @DisplayName("a MONTHLY schedule with an ACTUAL_DATE solve yields a 1-per-year rate")
        void theConventionIsIgnored() {
            // The rate is now wrapped with convention.periodsPerYear(), read from the same
            // eir_computation row that supplied rate_periodic. TimeConvention.ActualDate reports
            // ONE period a year by construction -- the stored rate is annual effective under that
            // convention -- so 1 is the answer and 12 was the defect.
            ContractStateSource.OpeningState state = main.contractState()
                .openingState(LatentDefectFixture.ACTUAL_DATE_ID, NOW).orElseThrow();

            assertThat(state.eir().periodsPerYear())
                .as("wrapped at the CONVENTION's periodicity; the schedule's MONTHLY basis decides"
                    + " the compounding step of a PERIODIC_INDEX solve and nothing about this one")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("the rate and the convention the pipeline receives agree, so the engine rolls")
        void theTwoPortsAgree() {
            // The assertion that matters, and the one the old version could not make: the two
            // ports hand the pipeline a PAIR, and AmortisationEngine.roll refuses a rate
            // compounding n times a year under a convention implying m != n -- "an annual
            // effective rate rolled on period ordinals, or the reverse". FR-905's barrier DOES
            // catch that -- isolate() catches every RuntimeException and rethrows only a run-level
            // InvariantBreachException -- so the cost was not an abort: every actual-date contract
            // was quarantined every period, which blocks the close and puts an arithmetic
            // precondition in the queue where a "two columns disagree" diagnosis belonged.
            //
            // Comparing the two ports' answers directly is the check, because it is the pair the
            // engine guards, and neither port alone can be inspected for it.
            ContractStateSource.OpeningState state = main.contractState()
                .openingState(LatentDefectFixture.ACTUAL_DATE_ID, NOW).orElseThrow();
            ContractPeriod period =
                main.periods().periodFor(LatentDefectFixture.ACTUAL_DATE_ID, NOW);

            assertThat(state.eir().periodsPerYear())
                .as("AmortisationEngine.roll's first guard, asserted here at the boundary where"
                    + " the two halves of the pair are produced")
                .isEqualTo(period.convention().periodsPerYear());
            assertThat(period.convention())
                .as("and the convention must still be the stored one; two ports agreeing on"
                    + " PERIODIC_INDEX for an ACTUAL_DATE solve would satisfy the guard and"
                    + " reinterpret an annual effective rate as a monthly one")
                .isInstanceOf(TimeConvention.ActualDate.class);
        }

        /**
         * <b>The unrecognised-convention branch cannot be reached through this schema, and saying
         * so is the honest result.</b>
         *
         * <p>The other half of this finding was a change of kind: mapping the stored text used to
         * raise a bare {@code PersistenceFailure}, which {@code JdbcContractStateSource}'s catch
         * does not convert, so one row carrying a value outside V1's
         * {@code eir_computation_convention_ck} would stop every contract in the run rather than
         * quarantine one. It raises a {@code ContractDataCondition} now.
         *
         * <p>That branch has no live test and cannot have one, because the constraint refuses the
         * INSERT — the database is the first line of defence and it holds. So this test asserts
         * the constraint instead of the branch it makes unreachable. Claiming a live test for the
         * mapper would be claiming coverage of a path no seed can produce; the branch remains as
         * defence against a later migration widening the CHECK or a database this module did not
         * migrate, which is a real deployment condition and not one reachable from here.
         */
        @Test
        @DisplayName("the schema refuses an unrecognised convention, so the mapper's branch is"
            + " unreachable from here")
        void anUnrecognisedConventionIsRefusedByTheSchema() {
            Throwable refusal = catchThrowable(() -> {
                try (var connection = dataSource.getConnection();
                     var statement = connection.createStatement()) {
                    statement.executeUpdate(
                        "UPDATE eir_computation SET convention = 'SEASONAL'"
                            + " WHERE contract_id = '" + LatentDefectFixture.ACTUAL_DATE_ID + "'");
                }
            });

            assertThat(refusal)
                .as("V1's eir_computation_convention_ck must reject it; if this ever passes, the"
                    + " constraint has been dropped and SolvedRateReader's default branch becomes"
                    + " the only thing standing between a bad row and a misread rate")
                .isNotNull();
            assertThat(refusal).hasMessageContaining("eir_computation_convention_ck");

            // And the row is unchanged, so the contract still reads as ACTUAL_DATE. Asserted
            // because a partially-applied UPDATE would leave the shared fixture altered for every
            // test after this one, and this class runs several against this contract.
            assertThat(main.contractState()
                .openingState(LatentDefectFixture.ACTUAL_DATE_ID, NOW).orElseThrow()
                .eir().periodsPerYear())
                .as("the refused UPDATE left the fixture as it was")
                .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("4. a misfiled flow is refused rather than silently dropped (FIXED)")
    class FlowWindowIsTheCalendarMonth {

        // ============================================================ what this class used to say
        //
        // aMisfiledFlowVanishes asserted that periodFor RETURNED a period for this contract, whose
        // vector held "one substituted boundary flow, not the scheduled instalment" -- a synthetic
        // zero-amount flow dated at the period end while a real 47,073.47 instalment sat in the
        // table. Its own comment named why that was the sharper finding: "an empty vector might
        // have been refused somewhere downstream, whereas a zero-amount flow is indistinguishable
        // from a genuinely payment-free period and passes every check."
        //
        // That is now refused at the read. What has NOT changed is the schema: nothing in V1 ties
        // cashflow_line.flow_date to cashflow_line.period_id, although period_id is the partition
        // key, so the row is still insertable -- which is why theRowIsPresentInTheTable below is
        // kept exactly as it was. The guard is a read-side diagnosis, not the constraint; see
        // FlowVectorReader.SELECT_MISFILED_LINES for why the obvious CHECK would be wrong (it
        // hard-codes the Gregorian assumption that readPeriodDates deliberately refuses to make).

        @Test
        @DisplayName("the contract is quarantined with both axes named, not given a nil vector")
        void aMisfiledFlowVanishes() {
            // periodFor is non-optional -- "a source that cannot answer at all is a defect in the
            // source" -- so the refusal is an exception, and it is a ContractDataCondition so that
            // FR-905's barrier files it against THIS contract and the run continues.
            Throwable refusal = catchThrowable(
                () -> main.periods().periodFor(LatentDefectFixture.MISFILED_FLOW_ID, NOW));

            assertThat(refusal)
                .as("computing on a vector known to be short publishes a payment-free period over"
                    + " a real instalment, which no downstream check can catch")
                .isInstanceOf(ContractDataCondition.class);
            // Both axes and the window, because "a flow is missing" is not a diagnosis. Whoever
            // works this queue entry has to find one row in a partitioned table, and the row is
            // identified by the pair that disagrees.
            assertThat(refusal)
                .hasMessageContaining("dated 2027-05-15")
                .hasMessageContaining("filed under period 202704")
                .hasMessageContaining("47073.47");
            assertThat(refusal)
                .as("and the window it fell between, or the reader cannot tell which of the two"
                    + " axes is the wrong one")
                .hasMessageContaining("2027-04-01")
                .hasMessageContaining("2027-04-30");
        }

        @Test
        @DisplayName("the row really is there, so the refusal above is the window and not the data")
        void theRowIsPresentInTheTable() throws Exception {
            // Without this, the assertion above would pass just as well if the insert had failed --
            // which is the shape of vacuous test this repository keeps finding. Kept verbatim from
            // when this class recorded the defect: the schema still admits the row, and that is
            // the sixth finding, still open.
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var rs = statement.executeQuery(
                     "SELECT flow_date, period_id, amount FROM cashflow_line"
                         + " WHERE contract_id = '" + LatentDefectFixture.MISFILED_FLOW_ID + "'")) {

                assertThat(rs.next()).as("the misfiled row was inserted").isTrue();
                assertThat(rs.getString("flow_date")).isEqualTo("2027-05-15");
                assertThat(rs.getInt("period_id")).isEqualTo(202704);
                assertThat(rs.getBigDecimal("amount"))
                    .isEqualByComparingTo(new BigDecimal("47073.470000"));
            }
        }

        /**
         * <b>A flow dated exactly on the period's start date is not refused, and this test exists
         * because a mutation showed nothing checked that.</b>
         *
         * <p>Changing the guard's lower date comparison from {@code <} to {@code <=} left the
         * entire live suite green — 117 tests — so the strictness the query's javadoc argues for
         * was an unverified assertion about a shape no fixture carried. It carries one now.
         *
         * <p>What it protects: the read window is half-open, {@code (start, end]}, so this flow is
         * not returned for period 202704 and by the "matched by no window" reasoning would look
         * lost. But under this fixture's calendar (202704 runs 2027-04-01 to 2027-04-30) no
         * adjacent period would claim it either, so filing it here is the only thing a feed could
         * sensibly do. Refusing it would quarantine every monthly loan due on the first of the
         * month — the whole book, for a boundary convention nobody has settled. The convention
         * question is recorded rather than decided inside a validation query.
         */
        @Test
        @DisplayName("a flow dated on the period start date is left alone, not refused")
        void aFlowOnTheBoundaryDateIsNotRefused() {
            assertThat(catchThrowable(
                () -> main.periods().periodFor(LatentDefectFixture.BOUNDARY_FLOW_ID, NOW)))
                .as("the guard must not decide the half-open-window boundary question by"
                    + " quarantining every contract that sits on it")
                .isNull();
        }

        @Test
        @DisplayName("a correctly filed contract is not refused by the new guard")
        void aWellFiledContractIsUntouched() {
            // The half that makes the guard usable rather than merely strict. A predicate written
            // slightly wrong -- NOT BETWEEN inverted, or the period bounds swapped -- would refuse
            // every contract in the book, and every other assertion in this class would still
            // pass because they all expect refusals. Asserted on the weekly contract, which has
            // four flows across four different dates inside one period and is therefore the
            // hardest correctly-filed case for a period predicate to get right.
            assertThat(catchThrowable(
                () -> main.periods().periodFor(LatentDefectFixture.WEEKLY_ID, NOW)))
                .as("four correctly filed flows in one period must pass the misfiling guard")
                .isNull();
        }
    }

    @Nested
    @DisplayName("5. a weekly contract's several boundaries are ONE published period (FIXED)")
    class WeeklyContract {

        // ============================================================ what this class used to say
        //
        // weeklyGivesFourFlows asserted the port's four flows and recorded that the pipeline then
        // refused them: "the refusal moved rather than went: AmortisationEngine.boundaries produces
        // one boundary per distinct discounting exponent, and ContractPipeline refuses
        // roll.periods() != 1."
        //
        // The port assertion was correct and is kept verbatim -- four instalments inside one
        // accounting month is what a weekly schedule IS, and the adapter reading them is the
        // adapter working. What has changed is the layer above: ContractPipeline now summarises
        // several accrual periods into the one movement a close publishes, through
        // AmortisationResult.asOneAccrualPeriod. So a second test is added for the half that used
        // to refuse -- which quarantined the contract rather than aborting the run, FR-905's
        // barrier catching the IllegalStateException like any other; the effect was that every
        // weekly and fortnightly loan in the book was unclosable, not that the run died.

        @Test
        @DisplayName("four April instalments come back as four flows in one period's vector")
        void weeklyGivesFourFlows() {
            // WEEKLY is one of the eight values V1's compounding_basis check admits, and
            // CompoundingBasis.stepOf goes to explicit trouble to support it -- its javadoc says an
            // earlier version refusing it meant "a ten-million-contract close dying on the first
            // weekly loan rather than quarantining it under FR-905".
            ContractPeriod period = main.periods().periodFor(LatentDefectFixture.WEEKLY_ID, NOW);

            assertThat(period.periodFlows().future())
                .as("four instalments inside one accounting month, which is what a weekly schedule"
                    + " IS -- so the vector the pipeline receives has four accrual boundaries")
                .hasSize(4);
            assertThat(period.periodFlows().future())
                .extracting(flow -> flow.date().toString())
                .containsExactly("2027-04-07", "2027-04-14", "2027-04-21", "2027-04-28");
        }

        @Test
        @DisplayName("the pipeline computes it as one movement instead of refusing it")
        void thePipelineSummarisesRatherThanRefusing() {
            // The end-to-end half, and it has to be end-to-end: the summation is unit-tested in
            // eir-calc against hand-derived figures (AmortisationResultCollapseTest), but what
            // failed here was the WIRING -- a refusal in ContractPipeline that no amount of
            // correct arithmetic below it would have reached.
            JdbcRunComposition composition = new JdbcRunComposition(dataSource, Fixtures.BOOK_ID);
            ContractComputation computation = composition.pipelines()
                .forSlice(
                    composition.requestFor("RUN-WEEKLY-01", Fixtures.PERIOD_ID, NOW),
                    PartitionKey.grain(Fixtures.PRODUCT_ID, "ENT-01"))
                .compute(LatentDefectFixture.WEEKLY_ID);

            // ONE row. Before the fix this call threw IllegalStateException -- "produced 4 accrual
            // boundaries from its vector; the month-end loop computes exactly one" -- and FR-905's
            // barrier filed it as a quarantine, so every weekly loan in the book landed in the
            // exception queue every period and the close could not be signed. Reaching this line
            // at all is the fix.
            assertThat(computation.row().period())
                .as("stamped with the ACCOUNTING period's ordinal, which the pipeline passes in,"
                    + " rather than with any of the four accrual boundaries' own ordinals")
                .isEqualTo(main.periods()
                    .periodFor(LatentDefectFixture.WEEKLY_ID, NOW).periodOrdinal());
            assertThat(computation.row().accrualExponent())
                .as("elapsed accrual time for the whole month: four weekly periods summed. The"
                    + " last boundary's exponent alone would report one week of accrual for a"
                    + " month, understating the interest by roughly three quarters")
                .isEqualByComparingTo(new BigDecimal("4"));
            // The roll-forward identity on the published row, which is what makes the summary
            // safe to post: AmortisationRow's constructor enforces it, so this is asserting that
            // the row the close will publish is the row the engine built rather than a figure
            // assembled alongside it.
            assertThat(computation.row().openingGca()
                .plus(computation.row().interestAccrued())
                .minus(computation.row().cashReceived()))
                .as("opening + interest - cash = closing, on the collapsed month")
                .isEqualTo(computation.row().closingGca());
            assertThat(computation.row().closingGca())
                .as("and the closing the pipeline publishes must be the closing the contract"
                    + " reached, which is what ContractComputation carries separately")
                .isEqualTo(computation.closingGca());
        }
    }
}
