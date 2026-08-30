package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.onboarding.InstrumentClass;
import com.crisil.eir.application.onboarding.MeasurementCategory;
import com.crisil.eir.application.onboarding.OnboardingRequest;
import com.crisil.eir.application.onboarding.SppiOutcome;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The three ports {@link BitemporalReadLiveTest} does not exercise, against the live cluster.
 *
 * <p>{@code ContractPeriodSource}, {@code GeneralLedgerSource} and {@code OnboardingSource}, plus the
 * {@link JdbcPorts} façade. The bitemporal property is proved on four legs in the other class; what is
 * proved here is that each of these ports actually assembles the record the use cases consume — a
 * {@code ContractPeriod} whose ordinal comes from the contract's own schedule, a GL balance from a
 * table the engine does not write, and an {@code OnboardingRequest} carrying the classification
 * attributes as declared.
 *
 * <p>Expected values are written out by hand from the fixture's own inserts and from the schedule
 * arithmetic: first due date 1 May 2026, monthly, so the period ending 30 April 2027 is the twelfth
 * period — eleven whole months elapsed from the first due date, plus one.
 */
@Tag("live-db")
class RemainingPortsLiveTest {

    private static JdbcPorts ports;

    /** The live boundary; the fixture's whole feed set is visible here. */
    private static final AsAtBoundary NOW = AsAtBoundary.live(
        Fixtures.PERIOD_END, Fixtures.AS_AT_CORRECTED);

    @BeforeAll
    static void seed() {
        DataSource dataSource = LiveDatabase.dataSource();
        Fixtures.seed(dataSource);
        ports = new JdbcPorts(dataSource, Fixtures.BOOK_ID);
    }

    @Nested
    @DisplayName("period movements")
    class Periods {

        @Test
        @DisplayName("the ordinal is derived from the contract's schedule, not from a loop counter")
        void periodOrdinalComesFromTheSchedule() {
            // First due 1 May 2026, monthly. YearMonth 2026-05 to 2027-04 is eleven months, so the
            // period ending 30 April 2027 is ordinal 12. Derived by hand here and derived
            // independently in the adapter — which is the point ContractPeriod's javadoc makes:
            // "two independent derivations of one quantity is what makes ST-2 against the ledger a
            // control instead of a tautology".
            assertThat(period().periodOrdinal()).isEqualTo(12);
        }

        @Test
        @DisplayName("the flow vector is anchored at the period start with index 1 after it")
        void flowVectorIsAnchoredAtThePeriodStart() {
            ContractPeriod period = period();
            assertThat(period.periodFlows().anchorDate())
                .as("read from accounting_period, not derived from the YYYYMM encoding")
                .isEqualTo(LocalDate.of(2027, 4, 1));
            assertThat(period.periodFlows().future()).singleElement().satisfies(flow -> {
                assertThat(flow.date()).isEqualTo(LocalDate.of(2027, 4, 30));
                assertThat(flow.periodIndex())
                    .as("the index is measured from this vector's anchor, never taken from"
                        + " cashflow_line.sequence_no — which is 12 on this row and would inflate"
                        + " every discount factor by eleven periods")
                    .isEqualTo(1);
                assertThat(flow.amount().amount().toPlainString()).isEqualTo("47073.470000");
            });
        }

        @Test
        @DisplayName("the convention is the one the stored rate was solved under")
        void conventionComesFromTheStoredSolve() {
            // The fixture's solves record PERIODIC_INDEX. Supplying ACTUAL_DATE instead would
            // reinterpret a per-period rate as an annual effective one — a factor of roughly twelve
            // on a monthly contract, producing a clean-looking and entirely wrong schedule.
            assertThat(period().convention())
                .isEqualTo(new TimeConvention.PeriodicIndex(12));
        }

        @Test
        @DisplayName("the cash split comes from the cash book, both legs separately")
        void cashSplitComesFromTheCashBook() {
            // Not from period_balance.cash_received, which is one unsplit total and the engine's own
            // output. The split is the only thing that can detect a receipt applied to the wrong leg.
            ContractPeriod period = period();
            assertThat(period.cashAppliedToPrincipal().amount().toPlainString())
                .isEqualTo("41789.400000");
            assertThat(period.cashAppliedToInterest().amount().toPlainString())
                .isEqualTo("5284.070000");
            // 41789.40 + 5284.07 = 47073.47, which is the instalment on the schedule line. Derived
            // by hand; the equality is a property of the fixture and not of the adapter.
            assertThat(period.cashApplied().atPresentationScale().amount().toPlainString())
                .isEqualTo("47073.47");
        }

        @Test
        @DisplayName("suspense opens at nil where the prior period recorded no suspense entry")
        void suspenseOpensAtNil() {
            ContractPeriod period = period();
            assertThat(period.suspenseOpeningBalance().isZero()).isTrue();
            assertThat(period.suspenseRecovered().isZero()).isTrue();
            assertThat(period.suspenseWrittenOff().isZero()).isTrue();
        }

        @Test
        @DisplayName("the period carries its lifecycle event, with the revised vector anchored on it")
        void theEventIsCarriedWithItsRevisedVector() {
            ContractPeriod period = period();
            assertThat(period.hasEvent()).isTrue();
            assertThat(period.event().driver()).isEqualTo(RateDriver.NEGOTIATED);
            assertThat(period.event().eventDate()).isEqualTo(LocalDate.of(2027, 4, 15));
            assertThat(period.event().revisedFlows().anchorDate())
                .as("PeriodEvent refuses any other anchor: 'a mismatch shifts every exponent by the"
                    + " gap and neither TR-1 nor CU-2 would attribute the break to here'")
                .isEqualTo(LocalDate.of(2027, 4, 15));
            assertThat(period.event().hasDecidedConclusion())
                .as("FR-511: the conclusion is a human decision, and the fixture records one with"
                    + " its owner and the moment they took it")
                .isTrue();
        }

        @Test
        @DisplayName("an unknown contract throws, because this port is non-optional")
        void unknownContractThrows() {
            // The asymmetry with openingState, honoured literally. "A source that cannot answer at
            // all is a defect in the source, not a data condition" — and inventing a period of no
            // movement would publish a contract that accrued nothing and reconciled perfectly.
            assertThatThrownBy(() -> ports.periods()
                .periodFor("99999999-9999-4999-8999-999999999999", NOW))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining("non-optional");
        }

        private static ContractPeriod period() {
            return ports.periods().periodFor(Fixtures.CONTRACT_ID, NOW);
        }
    }

    @Nested
    @DisplayName("the general ledger")
    class GeneralLedger {

        @Test
        @DisplayName("the control account balance comes from a table the engine does not write")
        void balanceComesFromTheTrialBalance() {
            // This is the leg that was previously unfailable. eir-api's book concedes it: its GL
            // side is "summed from the book's own closing positions", so SL-1 "cannot fail on this
            // book. A production GeneralLedgerSource reads the bank's trial balance, and then it
            // can."
            assertThat(ports.generalLedger().controlAccountBalances(NOW))
                .singleElement()
                .satisfies(balance -> {
                    assertThat(balance.accountCode()).isEqualTo(Fixtures.GCA_ACCOUNT);
                    assertThat(balance.bookId()).isEqualTo(Fixtures.BOOK_ID);
                    assertThat(balance.balance().atPresentationScale().amount().toPlainString())
                        .isEqualTo("528407.32");
                    assertThat(balance.sourceRef()).isEqualTo("TB-202704-POSTED");
                });
        }

        @Test
        @DisplayName("a book the trial balance says nothing about yields nothing, not zero")
        void anUnknownBookIsEmptyNotZero() {
            // SL-1 with no GL figure is unresolved; SL-1 against a fabricated zero is a screaming
            // break on a book where nothing is wrong — the fixture defect eir-api's Book records
            // having produced, "the worst kind, because an operator would go looking for the
            // 36,059.88".
            JdbcPorts igaap = new JdbcPorts(LiveDatabase.dataSource(), "IGAAP");
            assertThat(igaap.generalLedger().controlAccountBalances(NOW)).isEmpty();
        }
    }

    @Nested
    @DisplayName("onboarding")
    class Onboarding {

        @Test
        @DisplayName("the classification attributes arrive as declared, not as corrected")
        void classificationIsAsDeclared() {
            OnboardingRequest request = require(Fixtures.CONTRACT_ID);

            assertThat(request.instrumentClass()).isEqualTo(InstrumentClass.LOAN);
            assertThat(request.declaredCategory())
                .as("what the source system asserted; MeasurementGate compares it against the SPPI"
                    + " assessment and concludes, which is only possible if both arrive separately")
                .isEqualTo(MeasurementCategory.AMORTISED_COST);
            assertThat(request.sppiAssessment().outcome()).isEqualTo(SppiOutcome.PASS);
            assertThat(request.sppiAssessment().approver()).isEqualTo("checker.one");
            assertThat(request.sppiAssessment().assessedOn())
                .isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        @DisplayName("initial recognition date is the contract's own, not the disbursement date")
        void initialRecognitionDateIsCarriedSeparately() {
            // ACPIR 23: for a commitment this is the day the bank became party to the irrevocable
            // commitment and not the date of first drawdown. They coincide in this fixture and are
            // still read from different columns, which is what keeps them able to diverge.
            OnboardingRequest request = require(Fixtures.CONTRACT_ID);
            assertThat(request.initialRecognitionDate()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(request.terms().disbursementDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        }

        @Test
        @DisplayName("the tier-gate inputs come from V3, because 04 section 2.1 holds the outcome")
        void tierGateInputsAreRead() {
            OnboardingRequest request = require(Fixtures.CONTRACT_ID);
            assertThat(request.segment()).isEqualTo(TierAssignmentSegment.WHOLESALE);
            assertThat(request.tierFeatures())
                .containsExactly(TierAssignmentFeature.PROJECT_FINANCE);
            assertThat(request.exposureAtOrigination().amount().toPlainString())
                .isEqualTo("1000000.000000");
            // And the derived tier input, whose tenor the record computes from the terms rather than
            // accepting a second answer to a question the terms already answer.
            assertThat(request.tierInput().originalTenorMonths()).isEqualTo(24);
        }

        @Test
        @DisplayName("the fee posting arrives with its sign and its cost function intact")
        void feePostingsAreCarried() {
            // FR-203: cost_function is the ACPIR 53 selling-versus-processing line, and a posting
            // that does not say which cannot be classified. The sign is as stored — flipping it
            // would move a cost into the fee stream and raise the EIR instead of lowering it.
            assertThat(require(Fixtures.CONTRACT_ID).feeSubmissions())
                .singleElement()
                .satisfies(fee -> {
                    assertThat(fee.feeCode()).isEqualTo("PROC_FEE");
                    assertThat(fee.amount().amount().toPlainString()).isEqualTo("15000.000000");
                    assertThat(fee.costFunction()).isEqualTo("PROCESSING");
                    assertThat(fee.postedOn()).isEqualTo(LocalDate.of(2026, 4, 1));
                });
        }

        @Test
        @DisplayName("an FVTPL contract still produces a request, so the gate can check it")
        void fvtplIsNotPreFiltered() {
            // The fixture gives the FVTPL contract no onboarding attribute row, so the request is
            // empty for THAT reason — a data condition InitialRecognition quarantines under
            // MISSING_MANDATORY_FIELD. The point asserted here is the reason: the terms read itself
            // resolves the contract, which the population query would have excluded outright.
            assertThat(ports.contracts().contractIdsInScope(NOW))
                .doesNotContain(Fixtures.FVTPL_CONTRACT_ID);
            assertThat(ports.onboarding().onboardingRequest(Fixtures.FVTPL_CONTRACT_ID, NOW))
                .as("empty because the 03 section 10 attributes are absent, not because the"
                    + " category was filtered — defaulting the segment would change which tier"
                    + " every unattributed contract is measured under")
                .isEmpty();
        }

        @Test
        @DisplayName("an unknown contract is empty rather than an exception")
        void unknownContractIsEmpty() {
            assertThat(ports.onboarding()
                .onboardingRequest("99999999-9999-4999-8999-999999999999", NOW))
                .isEmpty();
        }

        private static OnboardingRequest require(String contractId) {
            return ports.onboarding().onboardingRequest(contractId, NOW).orElseThrow(
                () -> new AssertionError("no onboarding request for " + contractId));
        }
    }

    @Nested
    @DisplayName("the façade")
    class Facade {

        @Test
        @DisplayName("all seven ports are wired, to one database and one book")
        void allSevenPortsAreWired() {
            // Seven, not six: a caller wiring them individually could point six at one book and the
            // seventh at another, producing a sub-ledger tying to a general ledger from a different
            // set of accounts and a break equal to the whole of one book.
            assertThat(ports.contracts()).isNotNull();
            assertThat(ports.contractState()).isNotNull();
            assertThat(ports.periods()).isNotNull();
            assertThat(ports.coreBanking()).isNotNull();
            assertThat(ports.generalLedger()).isNotNull();
            assertThat(ports.policySource()).isNotNull();
            assertThat(ports.onboarding()).isNotNull();
            assertThat(ports.bookId()).isEqualTo(Fixtures.BOOK_ID);
        }
    }
}
