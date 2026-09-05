package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import com.crisil.eir.application.run.ContractPeriod;
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
 * says what they do. This class says what they do. **Every assertion below pins CURRENT behaviour,
 * not correct behaviour** — several of these should change, and when they do these tests must fail
 * and be rewritten to the new contract. A test asserting a defect is a record, not an endorsement.
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
    @DisplayName("1. both sides of RC-1 read one cbs_billed_interest row")
    class ReconciliationAgainstItself {

        @Test
        @DisplayName("the engine leg and the CBS leg are the same figure from the same row")
        void bothLegsComeFromOneRow() {
            // The most serious of the five, and the only one needing no unusual data at all.
            //
            // CoreBankingFeed's javadoc says the two ports are separate because "if the run took
            // both numbers from one port, the comparison would be a field against itself". Under
            // this wiring they are one port: JdbcCoreBankingFeed.SELECT_BILLED_FOR_PERIOD and
            // JdbcContractStateSource.SELECT_BILLED read the same cbs_billed_interest.billed_interest
            // row for the same contract and period under the same system-time predicate, and
            // OpeningState.contractualInterestBilled is what EirService builds RC-1's ENGINE leg
            // from while the feed is its CBS leg.
            List<CbsBilledInterest> feed = main.coreBanking().billedInterest(NOW);
            ContractStateSource.OpeningState state =
                main.contractState().openingState(Fixtures.CONTRACT_ID, NOW).orElseThrow();

            BigDecimal cbsLeg = feed.stream()
                .filter(row -> row.contractId().equals(Fixtures.CONTRACT_ID))
                .findFirst().orElseThrow()
                .billedInterest().amount();
            BigDecimal engineLeg = state.contractualInterestBilled().amount();

            assertThat(engineLeg)
                .as("RC-1's two legs are one column of one row, so its deviation is identically nil"
                    + " whatever the value -- a control comparing a field against itself")
                .isEqualByComparingTo(cbsLeg);
        }

        @Test
        @DisplayName("moving the row moves BOTH legs, which is the proof it cannot fail")
        void movingTheRowMovesBothLegs() throws Exception {
            // The decisive test. A genuine reconciliation has one side move and the other stay,
            // producing a deviation. Here a single UPDATE moves both, so no value of the column can
            // ever make RC-1 report a break. Restored afterwards so test order cannot matter.
            BigDecimal before = engineLeg();
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.executeUpdate(
                    "UPDATE cbs_billed_interest SET billed_interest = '99999.990000'"
                        + " WHERE contract_id = '" + Fixtures.CONTRACT_ID + "'"
                        + " AND period_id = " + Fixtures.PERIOD_ID);
            }
            try {
                assertThat(engineLeg())
                    .as("the engine leg followed the CBS row, because it IS the CBS row")
                    .isEqualByComparingTo(new BigDecimal("99999.990000"));
                assertThat(cbsLeg())
                    .as("and so did the feed leg -- both sides moved together")
                    .isEqualByComparingTo(new BigDecimal("99999.990000"));
                assertThat(engineLeg())
                    .as("no value of this column can separate the two legs, so RC-1's deviation is"
                        + " structurally nil and the control cannot fail")
                    .isEqualByComparingTo(cbsLeg());
            } finally {
                try (var connection = dataSource.getConnection();
                     var statement = connection.createStatement()) {
                    statement.executeUpdate(
                        "UPDATE cbs_billed_interest SET billed_interest = '" + before + "'"
                            + " WHERE contract_id = '" + Fixtures.CONTRACT_ID + "'"
                            + " AND period_id = " + Fixtures.PERIOD_ID);
                }
            }
        }

        private BigDecimal engineLeg() {
            return main.contractState().openingState(Fixtures.CONTRACT_ID, NOW).orElseThrow()
                .contractualInterestBilled().amount();
        }

        private BigDecimal cbsLeg() {
            return main.coreBanking().billedInterest(NOW).stream()
                .filter(row -> row.contractId().equals(Fixtures.CONTRACT_ID))
                .findFirst().orElseThrow()
                .billedInterest().amount();
        }
    }

    @Nested
    @DisplayName("2. the population query ignores the book it was given")
    class PopulationIgnoresTheBook {

        @Test
        @DisplayName("a run of book MAIN enumerates the IGAAP contract too")
        void mainEnumeratesTheOtherBook() {
            // FR-109's parallel books: the same facility measured on the ACPIR basis, on IGAAP and
            // on a tax basis. V1's contract_source_ref_uq is (source_system_ref, entity_id, book_id)
            // precisely so one facility can appear once per book -- and JdbcContractSource holds a
            // bookId, stores it, and never puts it in SELECT_POPULATION.
            List<String> population = main.contracts().contractIdsInScope(NOW);

            assertThat(population)
                .as("the IGAAP contract is in a MAIN run's population; the balances are scoped by"
                    + " book and the population is not, which is the mismatch JdbcAdapter's javadoc"
                    + " says the constructor-held book exists to prevent")
                .contains(LatentDefectFixture.OTHER_BOOK_ID);

            // And the consequence, which is worse than the enumeration itself: the contract has no
            // MAIN-book balance, so it is quarantined under FR-905 with a message about a missing
            // opening state rather than about a book. An operator reads "no state recorded" for a
            // contract that is simply not theirs.
            assertThat(main.contractState().openingState(LatentDefectFixture.OTHER_BOOK_ID, NOW))
                .as("no MAIN-book state exists for it, so the run quarantines it for the wrong"
                    + " reason")
                .isEmpty();
        }

        @Test
        @DisplayName("a run of book IGAAP enumerates the MAIN contracts too, symmetrically")
        void theOtherBookEnumeratesMain() {
            JdbcPorts igaap = new JdbcPorts(dataSource, LatentDefectFixture.OTHER_BOOK);

            assertThat(igaap.contracts().contractIdsInScope(NOW))
                .as("unscoped in both directions, so neither book's run is a run of that book")
                .contains(Fixtures.CONTRACT_ID, LatentDefectFixture.OTHER_BOOK_ID);
        }
    }

    @Nested
    @DisplayName("3. the stored rate is wrapped without consulting its convention")
    class RateWrappedWithoutItsConvention {

        @Test
        @DisplayName("a MONTHLY schedule with an ACTUAL_DATE solve yields a 12-per-year rate")
        void theConventionIsIgnored() {
            // readRateInForce builds Rate.periodic(value, terms.periodsPerYear()) unconditionally.
            // periodsPerYear comes from compounding_basis (MONTHLY -> 12) and the stored
            // eir_computation.convention is never read -- although JdbcContractPeriodSource reads
            // that very column for the same solve, through SELECT_SOLVED_CONVENTION.
            ContractStateSource.OpeningState state = main.contractState()
                .openingState(LatentDefectFixture.ACTUAL_DATE_ID, NOW).orElseThrow();

            assertThat(state.eir().periodsPerYear())
                .as("wrapped at the SCHEDULE's frequency, with the stored ACTUAL_DATE convention"
                    + " unread")
                .isEqualTo(12);

            // Why that matters: TimeConvention.ActualDate reports one period a year by construction,
            // and AmortisationEngine.roll refuses a rate compounding 12 times a year under a
            // convention implying 1 -- "an annual effective rate rolled on period ordinals, or the
            // reverse". So the pair the ports hand the pipeline is one the engine will not roll, and
            // the contract aborts rather than being quarantined with a reason naming the mismatch.
            // And the stored convention, read by a DIFFERENT query on the same solve
            // (JdbcContractPeriodSource.SELECT_SOLVED_CONVENTION). The two disagree: one says the
            // rate compounds twelve times a year, the other says the accrual is measured on actual
            // dates. TimeConvention.ActualDate reports ONE period a year by construction.
            //
            // MEASURED: no refusal at the port boundary. The disagreeing pair is handed on intact.
            // An earlier version of this test hedged with an if/else over both outcomes; that is a
            // weaker test than the one fact deserves, so the fact is pinned now that it is known.
            assertThat(catchThrowable(() -> main.periods()
                .periodFor(LatentDefectFixture.ACTUAL_DATE_ID, NOW)))
                .as("the adapter reads two columns that contradict each other and refuses neither,"
                    + " so the contradiction travels into the roll-forward. If a refusal comes at"
                    + " all it comes from AmortisationEngine.roll, which names an arithmetic"
                    + " precondition rather than the two disagreeing columns an operator would have"
                    + " to go and fix")
                .isNull();
        }
    }

    @Nested
    @DisplayName("4. the flow window is the calendar month, not the accrual period")
    class FlowWindowIsTheCalendarMonth {

        @Test
        @DisplayName("a flow filed under 202704 and dated in May is invisible to that period")
        void aMisfiledFlowVanishes() {
            // Legal data: nothing in V1 ties cashflow_line.flow_date to cashflow_line.period_id,
            // although period_id is the table's PARTITION KEY. So a feed that computed the period
            // wrongly files an economically-real flow into a partition whose window excludes it.
            ContractPeriod period =
                main.periods().periodFor(LatentDefectFixture.MISFILED_FLOW_ID, NOW);

            // NOT empty -- and what is there is the finding. FlowVectorReader.boundaryOnly
            // substitutes a SYNTHETIC ZERO-AMOUNT flow at the period end, so the pipeline receives
            // a well-formed vector describing a period in which nothing was due. The real
            // 47,073.47 instalment is in the table, dated inside the contract's life, and absent
            // from the only vector that would have carried it.
            //
            // This is a sharper demonstration than an empty vector would have been: an empty
            // vector might have been refused somewhere downstream, whereas a zero-amount flow is
            // indistinguishable from a genuinely payment-free period and passes every check.
            assertThat(period.periodFlows().future())
                .as("one substituted boundary flow, not the scheduled instalment")
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.amount().isZero())
                        .as("a zero-amount synthetic flow stands in for a 47,073.47 instalment")
                        .isTrue();
                    assertThat(flow.date().toString())
                        .as("dated at the period end, which is the boundary and not the schedule")
                        .isEqualTo("2027-04-30");
                });
            assertThat(period.periodFlows().anchorDate())
                .as("anchored at the accounting period's start, read from accounting_period")
                .isEqualTo(java.time.LocalDate.of(2027, 4, 1));
        }

        @Test
        @DisplayName("the row really is there, so the emptiness above is the window and not the data")
        void theRowIsPresentInTheTable() throws Exception {
            // Without this, the assertion above would pass just as well if the insert had failed --
            // which is the shape of vacuous test this repository keeps finding.
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
    }

    @Nested
    @DisplayName("5. a weekly contract yields several accrual boundaries in one period")
    class WeeklyContract {

        @Test
        @DisplayName("four April instalments come back as four flows in one period's vector")
        void weeklyGivesFourFlows() {
            // WEEKLY is one of the eight values V1's compounding_basis check admits, and
            // CompoundingBasis.stepOf goes to explicit trouble to support it -- its javadoc says an
            // earlier version refusing it meant "a ten-million-contract close dying on the first
            // weekly loan rather than quarantining it under FR-905". The refusal moved rather than
            // went: AmortisationEngine.boundaries produces one boundary per distinct discounting
            // exponent, and ContractPipeline refuses roll.periods() != 1.
            ContractPeriod period = main.periods().periodFor(LatentDefectFixture.WEEKLY_ID, NOW);

            assertThat(period.periodFlows().future())
                .as("four instalments inside one accounting month, which is what a weekly schedule"
                    + " IS -- so the vector the pipeline receives has four accrual boundaries")
                .hasSize(4);
            assertThat(period.periodFlows().future())
                .extracting(flow -> flow.date().toString())
                .containsExactly("2027-04-07", "2027-04-14", "2027-04-21", "2027-04-28");
        }
    }
}
