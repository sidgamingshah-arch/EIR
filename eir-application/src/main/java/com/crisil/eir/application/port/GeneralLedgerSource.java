package com.crisil.eir.application.port;

import com.crisil.eir.gl.posting.GlControlAccountBalance;
import java.util.List;

/**
 * The general ledger's control account balances — the other side of SL-1 (FR-803).
 *
 * <p>Same argument as {@link CoreBankingFeed}: SL-1 reconciles the engine's sub-ledger against what
 * the GL reports, and deriving the GL side from the sub-ledger would make the invariant unfailable.
 * 08's scope table is explicit that "the engine posts to a general ledger; it is not one", so this
 * is a read of somebody else's system.
 */
public interface GeneralLedgerSource {

    /** The GL's control account balances as at the boundary. */
    List<GlControlAccountBalance> controlAccountBalances(AsAtBoundary boundary);
}
