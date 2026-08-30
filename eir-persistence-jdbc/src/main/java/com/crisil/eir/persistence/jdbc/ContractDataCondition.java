package com.crisil.eir.persistence.jdbc;

/**
 * One row the adapter cannot interpret — a data condition on one contract, not a broken environment.
 *
 * <h2>Why this is a separate type</h2>
 *
 * <p>{@link JdbcContractStateSource} promises that {@code openingState} "returns
 * {@code Optional.empty()} and never throws for a data condition, because a contract in the
 * population that has no state as at the boundary is a data condition the run reports per contract —
 * FR-905 — and not a reason to abandon a ten-million-contract close". Absence was honoured; a row
 * present but unreadable was not. A product tagged {@code REPRICING_SHORTCUT} — which V1 admits, and
 * keeps coherent with {@code product_b544_implies_next_repricing_ck}, so a bank that has made the
 * B5.4.4 election has real products carrying it — made {@code openingState} throw, and every
 * contract on that product aborted the run.
 *
 * <p>That is the same class of failure as returning {@code Optional.empty()} on a dropped connection,
 * only in the other direction: one is a data condition reported as an outage, this was an outage
 * reported for a data condition. {@link PersistenceFailure}'s own javadoc scopes it to "the adapter's
 * environment being broken", which one mis-vocabularised row is not.
 *
 * <p>So the two are distinguished by type, and the ports that are allowed to answer "nothing" catch
 * exactly this one:
 *
 * <ul>
 *   <li>{@code ContractStateSource.openingState} — empty, and FR-905 quarantines the contract.
 *   <li>{@code OnboardingSource.onboardingRequest} — empty; {@code InitialRecognition} quarantines it
 *       under {@code MISSING_MANDATORY_FIELD}.
 *   <li>{@code ContractPeriodSource.periodFor} — <b>propagates</b>, because that port is
 *       deliberately non-optional. In practice the pipeline never reaches it for such a contract:
 *       {@code openingState} answered empty first and the contract was quarantined. The exception is
 *       therefore the right outcome if it is ever reached — it means something asked for the
 *       movements of a contract the run had already set aside.
 * </ul>
 *
 * <p><b>It is still a throw and not a returned {@code Optional} at the point of failure</b>, because
 * the refusals it carries are deep inside row mapping — a day count, a rate type, a currency code, a
 * projection strategy — and threading an optional back out of five nested readers would put a
 * silently-skipped branch at every level. One type, caught in the two places the port contract
 * permits, keeps the decision visible.
 */
public final class ContractDataCondition extends PersistenceFailure {

    private static final long serialVersionUID = 1L;

    ContractDataCondition(String message) {
        super(message);
    }
}
