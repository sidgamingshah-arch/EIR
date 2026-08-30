package com.crisil.eir.batch;

import java.util.List;

/**
 * Which {@code product × entity} grain each contract in the population belongs to (ADR-0007).
 *
 * <p><b>Why a batch method and not one call per contract.</b> A run enumerates ten million contract
 * ids from {@code ContractSource} and then has to group them; ten million individual lookups
 * against the contract master is the shape that turns a four-hour close window into a six-hour one
 * before a single figure has been computed. The persistence implementation of this port is one
 * grouped query — {@code select contract_id, product_code, entity_code from contract where …} — and
 * the interface is written so that it can be.
 *
 * <p><b>No {@code AsAtBoundary}.</b> Deliberate, and it is the one asymmetry with the seven ports in
 * {@code eir-application.port}. Those ports answer questions whose answers are <em>figures</em>, so
 * a replay must read them as at the original run's {@code recorded_at} or it reproduces nothing.
 * This port answers a question about <em>how the work is divided up</em>, and the division has no
 * effect on any published figure: the same contracts are computed by the same pipeline whatever
 * partition they land in, and {@code RunAggregate} reorders results into population order regardless
 * of which shard produced them. Taking a boundary here would imply that a replay whose grain
 * assignment had since changed reproduces differently, which would be a false statement about this
 * engine — and a true one would be a defect worth failing DT-1 over rather than a port signature.
 *
 * <p>{@code AmortisationBatchJobTest.partitionShapeDoesNotMoveAFigure} is where that claim is
 * checked rather than asserted in prose.
 */
public interface PartitionGrainSource {

    /**
     * The grain each of {@code contractIds} belongs to, in the same order.
     *
     * <p>The returned list must be the same length as the argument and must carry a grain for every
     * contract — {@link PartitionPlan#over} refuses a short answer rather than dropping the
     * contracts it did not cover, because a contract with no partition is a contract in the
     * population that no worker runs, and that is the "processed 9,999,998" shortfall arriving
     * before any arithmetic has happened.
     *
     * @param contractIds the population, in run order
     * @return one {@link PartitionKey#grain} per contract id, positionally aligned; shard ordinals
     *         are the plan's to assign and must be zero here
     */
    List<PartitionKey> grainsOf(List<String> contractIds);
}
