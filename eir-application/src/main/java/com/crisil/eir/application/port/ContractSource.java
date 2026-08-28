package com.crisil.eir.application.port;

import java.util.List;

/**
 * The contracts in scope for a run, as at the boundary (05 § 3.2).
 *
 * <p><b>Returns ids, not contracts.</b> A run over ten million contracts cannot hold them in
 * memory, and 05 § 3.2 partitions "by product x entity" and loads "each contract, chunked". So the
 * population is enumerated separately from the loading, and the run-level count that FR-905's
 * isolation depends on — every contract in the population is either computed or quarantined —
 * comes from here rather than from whatever the loader happened to return.
 */
public interface ContractSource {

    /**
     * Every contract id the run must account for.
     *
     * @param boundary the as-at boundary; a replay sees the population the original run saw, not
     *                 today's, or a contract onboarded since would appear in the replay and not in
     *                 the published figures
     */
    List<String> contractIdsInScope(AsAtBoundary boundary);
}
