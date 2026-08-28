package com.crisil.eir.application.port;

import com.crisil.eir.policy.registry.PolicyVersionRegistry;

/**
 * The policy versions in force, as at the boundary (05 § 3.3, FR-903).
 *
 * <p><b>The registry is supplied, not built here.</b> A run resolving policy from a store it read
 * itself would resolve today's versions, and 05 § 3.3 requires a replay to read "the policy and
 * rule-set versions effective then". Handing the whole registry across the port keeps the temporal
 * question in one place — {@code PolicyVersionRegistry.inForceOn}, with its latest-wins rule — and
 * keeps DT-1's policy leg able to compare what each run actually consulted.
 */
public interface PolicySource {

    /** The registry as the boundary sees it. */
    PolicyVersionRegistry policyVersions(AsAtBoundary boundary);
}
