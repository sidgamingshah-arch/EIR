package com.crisil.eir.policy.preview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What "stored" means to the gate: the previews held for a policy version, newest last.
 *
 * <p>FR-210 says the preview must be <em>stored</em>, and 04 § 2.12 persists it as
 * {@code impact_preview_ref}. This is not that store — persistence is {@code eir-persistence}'s
 * and this module is framework-free (ADR-0001). It exists so that "is there a stored preview for
 * this version?" has one implementation with one selection rule, rather than each caller
 * reaching into a map and picking a preview by whatever order it happened to iterate in.
 *
 * <p><b>Immutable; {@link #with} returns a new register.</b> A mutable store would make the
 * gate's answer depend on when it was asked relative to some other thread, and the gate's answer
 * is an audit record. The copy is cheap at the scale this operates on — a policy version accrues
 * previews in single digits, not thousands.
 *
 * <p>Several previews per version id is the normal case, not an edge one: a draft is revised,
 * re-previewed, revised again. Keeping them all is deliberate — the sequence is the evidence of
 * how the change was arrived at, and discarding all but the latest would also discard the record
 * that an earlier, larger impact was seen and the draft narrowed in response.
 */
public final class ImpactPreviewRegister {

    private static final ImpactPreviewRegister EMPTY =
        new ImpactPreviewRegister(Collections.unmodifiableMap(new LinkedHashMap<>()));

    /**
     * Insertion-ordered so that {@link #latestFor} can break a {@code generatedAt} tie by
     * storage order, and so that iteration is reproducible for invariant DT-1.
     */
    private final Map<String, List<ImpactPreview>> byVersionId;

    private ImpactPreviewRegister(Map<String, List<ImpactPreview>> byVersionId) {
        this.byVersionId = byVersionId;
    }

    /** A register holding nothing. */
    public static ImpactPreviewRegister empty() {
        return EMPTY;
    }

    /** A register holding exactly {@code previews}, in the order given. */
    public static ImpactPreviewRegister of(ImpactPreview... previews) {
        Objects.requireNonNull(previews, "previews");
        ImpactPreviewRegister register = empty();
        for (ImpactPreview preview : previews) {
            register = register.with(preview);
        }
        return register;
    }

    /** This register plus {@code preview}. */
    public ImpactPreviewRegister with(ImpactPreview preview) {
        Objects.requireNonNull(preview, "preview");
        Map<String, List<ImpactPreview>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<ImpactPreview>> entry : byVersionId.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        copy.computeIfAbsent(preview.policyVersionId(), key -> new ArrayList<>()).add(preview);
        // LinkedHashMap wrapped unmodifiable rather than Map.copyOf: Map.copyOf makes no
        // ordering promise, and this register's iteration order is part of what makes the gate's
        // audit sentence reproducible across runs over the same data (invariant DT-1).
        Map<String, List<ImpactPreview>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<ImpactPreview>> entry : copy.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new ImpactPreviewRegister(Collections.unmodifiableMap(frozen));
    }

    /** Every preview stored for {@code policyVersionId}, in storage order. */
    public List<ImpactPreview> storedFor(String policyVersionId) {
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        return byVersionId.getOrDefault(policyVersionId, List.of());
    }

    /** Whether anything at all has been previewed for {@code policyVersionId}. */
    public boolean hasAnyFor(String policyVersionId) {
        return !storedFor(policyVersionId).isEmpty();
    }

    /**
     * The newest preview for {@code policyVersionId} regardless of which draft it measured.
     *
     * <p>Used to <em>describe</em> a {@link ActivationRefusalReason#STALE_DRAFT_PREVIEW}
     * refusal — the message says which draft was previewed and when — and never to satisfy the
     * gate. Recency is not relevance: the newest preview of the wrong draft is precisely the
     * record the gate exists to reject.
     */
    public Optional<ImpactPreview> newestFor(String policyVersionId) {
        List<ImpactPreview> all = newestFirst(storedFor(policyVersionId));
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * The newest preview for {@code policyVersionId} that was computed against the draft
     * {@code draft} names. This is what the gate selects on.
     *
     * <p>Matching on content and then taking the newest, rather than taking the newest and then
     * checking it matches: a maker who previews a draft, edits it, then reverts the edit has a
     * valid preview of the current content, and refusing it would push them to re-run a preview
     * whose figures would be identical. The age horizon is applied separately by the gate, so
     * accepting an older matching preview here does not accept an indefinitely old one.
     */
    public Optional<ImpactPreview> newestFor(String policyVersionId, DraftFingerprint draft) {
        List<ImpactPreview> matching = matchingFor(policyVersionId, draft);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(0));
    }

    /**
     * Every preview of the draft {@code draft} names, newest first.
     *
     * <p>The gate walks this list rather than taking only its head, because the newest matching
     * preview can be unusable for a reason that has nothing to do with the draft — a skewed
     * clock, a broken aggregation — while an older one of the same content is perfectly good.
     * Refusing on the head alone would produce a false refusal with a usable preview sitting in
     * the register, which is the same failure mode {@link #newestFor(String, DraftFingerprint)}
     * matches on content first to avoid.
     */
    public List<ImpactPreview> matchingFor(String policyVersionId, DraftFingerprint draft) {
        Objects.requireNonNull(draft, "draft");
        List<ImpactPreview> matching = new ArrayList<>();
        for (ImpactPreview preview : storedFor(policyVersionId)) {
            if (preview.coversDraft(draft)) {
                matching.add(preview);
            }
        }
        return newestFirst(matching);
    }

    /**
     * Sorted newest by {@code generatedAt}, ties broken by storage order — the later-stored
     * wins.
     *
     * <p>The tie is reachable: two previews of the same draft generated inside the same clock
     * tick, or two carrying a deliberately identical timestamp from a replayed fixture. A stable
     * rule matters more than which way it goes, because an unstable one would make the gate's
     * audit sentence differ between two runs over the same stored data, which is invariant DT-1's
     * concern. Implemented by reversing storage order and then sorting descending with a stable
     * sort, so a tie leaves the later-stored preview ahead.
     */
    private static List<ImpactPreview> newestFirst(List<ImpactPreview> previews) {
        List<ImpactPreview> ordered = new ArrayList<>(previews);
        Collections.reverse(ordered);
        ordered.sort(Comparator.comparing(ImpactPreview::generatedAt).reversed());
        return List.copyOf(ordered);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("impact preview register: ");
        if (byVersionId.isEmpty()) {
            return out.append("empty").toString();
        }
        boolean first = true;
        for (Map.Entry<String, List<ImpactPreview>> entry : byVersionId.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(" x").append(entry.getValue().size());
            first = false;
        }
        return out.toString();
    }
}
