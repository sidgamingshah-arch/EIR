package com.crisil.eir.policy.tier;

/**
 * The counterparty segment a contract is booked in, as far as the materiality gate needs it
 * (03 § 10).
 *
 * <p><b>Four values, and no more, because § 10 draws only three segment lines.</b> The Tier 1
 * row keys on "wholesale above a Board threshold"; the Tier 2 row keys on "retail and MSME";
 * the Tier 3 row names money-market instruments. Nothing else in § 10 is segment-driven — the
 * rest is tenor-driven, which is the section's organising insight: "estimation risk distributes
 * by <b>tenor</b>, not by product". A richer product taxonomy belongs in {@code PRODUCT}
 * (04 § 2.2), and duplicating it here would invite the gate to key on product codes and thereby
 * lose the one property that makes § 10 defensible.
 *
 * <p>This is deliberately <em>not</em> the {@code instrument_class} of 04 § 2.1
 * ({@code LOAN | INVESTMENT | OFF_BALANCE_SHEET | LIABILITY}). A wholesale term loan and a
 * wholesale bond holding are different instrument classes and the same segment for tiering
 * purposes; a retail housing loan and a wholesale project loan are the same instrument class
 * and different segments. Conflating the two would silently move exposures between tiers.
 */
public enum TierAssignmentSegment {

    /**
     * Corporate, institutional and government lending, and the wholesale investment book.
     *
     * <p>The only segment the Board threshold applies to (§ 10 Tier 1 row). A wholesale
     * exposure <em>below</em> the threshold is not thereby Tier 3: see
     * {@link TierAssignmentRule#TIER_1_DEFAULT_CONTRACT_LEVEL} for what catches it and why.
     */
    WHOLESALE,

    /**
     * Individuals — housing, LAP, auto, personal, consumer durable, education, gold, CV/CE.
     *
     * <p>Named in the § 10 Tier 2 row jointly with {@link #MSME}, and pooled under the
     * ACPIR 51 group presumption only above 12 months' original tenor. A retail temporary
     * overdraft is retail and Tier 3, because tenor decides.
     */
    RETAIL,

    /** Micro, small and medium enterprises — MSME term loans and working capital. */
    MSME,

    /**
     * The treasury and money-market book — T-bills, CP, CD, call and notice money, TREPS,
     * market repo, and the longer-dated HTM holdings.
     *
     * <p>Both ends of § 10 live here and the tenor rule separates them, which is the clearest
     * demonstration in the section that product is the wrong key. The money-market instruments
     * are all inside 12 months and reach Tier 3; the "long-dated HTM SDLs and corporate bonds"
     * that § 10's preamble puts among the five families holding roughly 80% of the bank's EIR
     * estimation risk are the same segment at a different tenor, and must not.
     */
    TREASURY
}
