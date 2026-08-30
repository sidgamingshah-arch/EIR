package com.crisil.eir.api.modules.policy;

import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.preview.DraftFingerprint;
import com.crisil.eir.policy.preview.ImpactPreview;
import com.crisil.eir.policy.preview.ImpactPreviewRegister;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The policy versions this server holds, the content fingerprint of each draft, and the impact
 * previews stored against them — 04 § 2.12's {@code POLICY_VERSION} and {@code impact_preview_ref}
 * as an in-memory book, for 06 § 5's four endpoints.
 *
 * <p><b>Why a store of its own rather than the book's {@code PolicyVersionRegistry}.</b>
 * {@link PolicyVersionRegistry} is immutable by construction and refuses at construction any pair
 * of <em>approved</em> versions of one kind sharing an effective date — both of which are exactly
 * right for the thing a run resolves against, and both of which make it the wrong type to hold a
 * queue of drafts being edited and approved one at a time. So the registry seeds this store and
 * then this store is what the lifecycle endpoints mutate. Nothing here resolves a version for a
 * computation; that stays the registry's job.
 *
 * <p><b>The fingerprint is stored beside the version, and that is the whole reason this class is
 * not a {@code Map<String, PolicyVersion>}.</b> {@link DraftFingerprint} is content identity, and
 * {@code PolicyVersion} deliberately carries no fingerprint field — the version id is stable
 * across draft edits so that a computation can cite it. The impact-preview gate matches a stored
 * preview against the draft it measured, so somebody has to hold the mapping from "version id" to
 * "what that id says right now". Here it is held explicitly rather than recomputed at each call
 * site, because two call sites computing a fingerprint from slightly different parts is precisely
 * how a gate that compares fingerprints starts refusing everything or accepting everything.
 *
 * <p><b>Synchronised, and that is not on its own enough — so the two writes are compare-and-set.</b>
 * {@code EirServer} runs one executor thread today, so contention is currently impossible, which is
 * the reason to design for it rather than to rely on it. Per-method locking would <em>not</em> have
 * been enough: the approval path reads a version, asks two gates about it outside any lock, and
 * then writes a replacement, so two concurrent approvals of one id would both read {@code DRAFT},
 * both pass, and the second write would overwrite the first checker's signature with no record that
 * it existed. Making every method {@code synchronized} and calling it safe is exactly the kind of
 * claim this codebase treats as worse than an absent one. So the two state changes take the state
 * they expect to find as an argument — {@link #draftIfAbsent} and {@link #replaceIfAt} — and refuse
 * rather than clobber when the world has moved under the caller.
 */
public final class PolicyVersionsStore {

    /** Versions by id, in the order they arrived — seeded ones first, then drafts. */
    private final Map<String, PolicyVersion> versions = new LinkedHashMap<>();

    /** What each version's content says right now, as a digest. */
    private final Map<String, DraftFingerprint> fingerprints = new LinkedHashMap<>();

    /**
     * The free-text draft content each version was drafted with, or empty.
     *
     * <p>Retained because it is a fingerprint <em>pre-image</em> and therefore the only thing that
     * can explain a {@code STALE_DRAFT_PREVIEW} refusal to the person who has to fix it. A digest
     * alone says the draft changed; it cannot say into what.
     */
    private final Map<String, String> content = new LinkedHashMap<>();

    /** FR-210's "stored" impact previews. Replaced wholesale — the register is immutable. */
    private ImpactPreviewRegister previews = ImpactPreviewRegister.empty();

    private PolicyVersionsStore() {
    }

    /**
     * A store carrying every version the book's registry holds, then open for drafting.
     *
     * <p>Seeded rather than started empty so that {@code GET /api/policy-versions} answers with
     * the versions that are actually in force over the book — the three of {@code Seed.policies()}
     * — and not only with whatever a caller has drafted since the process started. A list endpoint
     * that omitted the effective versions would show a checker an empty policy file for a book
     * whose every figure cites one.
     */
    public static PolicyVersionsStore seededFrom(PolicyVersionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        PolicyVersionsStore store = new PolicyVersionsStore();
        for (PolicyVersion version : registry.versions()) {
            store.put(version, "");
        }
        return store;
    }

    /**
     * The content parts a fingerprint is taken over, in one place.
     *
     * <p>Every field a maker can change is included, and the free-text {@code content} is included
     * last. Leaving any of them out would make an edit to that field invisible to the gate: a
     * preview taken before the effective date moved would go on matching, and 06 § 5's whole point
     * is that a version's <em>content</em> and not its id is what a preview measures.
     *
     * <p>{@link DraftFingerprint#of(List)} length-prefixes each part, so no two different drafts
     * can produce one pre-image by moving a boundary between fields.
     */
    public static DraftFingerprint fingerprintOf(PolicyVersion version, String draftContent) {
        Objects.requireNonNull(version, "version");
        return DraftFingerprint.of(List.of(
            version.id(),
            version.kind().name(),
            version.description(),
            version.effectiveFrom().toString(),
            version.maker(),
            draftContent == null ? "" : draftContent));
    }

    /** Whether a version with this id is already held. */
    public synchronized boolean holds(String id) {
        return versions.containsKey(Objects.requireNonNull(id, "id"));
    }

    /** The version, or empty. */
    public synchronized Optional<PolicyVersion> find(String id) {
        return Optional.ofNullable(versions.get(Objects.requireNonNull(id, "id")));
    }

    /** Every version held, in arrival order. */
    public synchronized List<PolicyVersion> all() {
        return List.copyOf(new ArrayList<>(versions.values()));
    }

    /** The content identity of what {@code id} says now. */
    public synchronized DraftFingerprint currentDraftOf(String id) {
        DraftFingerprint fingerprint = fingerprints.get(Objects.requireNonNull(id, "id"));
        if (fingerprint == null) {
            throw new IllegalStateException(
                "no draft fingerprint is held for policy version " + id
                    + "; a version reached the store without one, so the impact-preview gate has"
                    + " nothing to match a stored preview against (FR-210)");
        }
        return fingerprint;
    }

    /** The free-text content {@code id} was drafted with; empty for a seeded version. */
    public synchronized String contentOf(String id) {
        return content.getOrDefault(Objects.requireNonNull(id, "id"), "");
    }

    /**
     * Adds a version and fingerprints it, unless the id is already held.
     *
     * <p>The test and the write are one operation on purpose. A caller that asked {@link #holds}
     * and then wrote would, under two threads, both find the id free and both write — and the
     * loser's response would have to be an exception mapped to a 500, where the answer the caller
     * needs is the ordinary refusal "that id is taken".
     *
     * @return whether the version was added; false means the id was already held and nothing was
     *     written — overwriting would discard whatever has been approved or previewed against it
     */
    public synchronized boolean draftIfAbsent(PolicyVersion version, String draftContent) {
        Objects.requireNonNull(version, "version");
        if (versions.containsKey(version.id())) {
            return false;
        }
        put(version, draftContent);
        return true;
    }

    /**
     * Replaces a held version with the same version moved along its life cycle.
     *
     * <p>The fingerprint is deliberately <b>not</b> recomputed. A status change is not a content
     * change, and re-fingerprinting on approval would invalidate the very preview the approval was
     * granted on — so the gate would refuse the version at activation for staleness, having just
     * permitted it at approval. Recomputing here would also mean the digest silently depended on
     * status, which is not draft content.
     *
     * <p><b>Compare-and-set on the status the caller read.</b> {@code expectedFrom} is the status
     * the version was in when the caller took it out and started asking gates about it. If the held
     * version has moved since, the write is refused: the gates were asked about a version that no
     * longer exists, and applying their answer anyway is how a second checker's approval silently
     * replaces a first one that has already been relied on.
     *
     * @return whether the replacement was written; false means the held version is no longer at
     *     {@code expectedFrom} and nothing was changed
     * @throws IllegalStateException if the id is not held — a caller replacing a version it never
     *     read is a defect here, not a race
     */
    public synchronized boolean replaceIfAt(
        PolicyVersion moved, PolicyVersionStatus expectedFrom) {
        Objects.requireNonNull(moved, "moved");
        Objects.requireNonNull(expectedFrom, "expectedFrom");
        PolicyVersion held = versions.get(moved.id());
        if (held == null) {
            throw new IllegalStateException(
                "policy version " + moved.id() + " is not held, so there is nothing to replace");
        }
        if (held.status() != expectedFrom) {
            return false;
        }
        versions.put(moved.id(), moved);
        return true;
    }

    /** The previews stored for every version — what the activation gate reads. */
    public synchronized ImpactPreviewRegister previews() {
        return previews;
    }

    /** Stores one preview. Earlier previews for the same version are kept, never replaced. */
    public synchronized void store(ImpactPreview preview) {
        previews = previews.with(Objects.requireNonNull(preview, "preview"));
    }

    /** How many previews are stored for {@code id}. */
    public synchronized int previewsStoredFor(String id) {
        return previews.storedFor(Objects.requireNonNull(id, "id")).size();
    }

    private void put(PolicyVersion version, String draftContent) {
        String text = draftContent == null ? "" : draftContent;
        versions.put(version.id(), version);
        content.put(version.id(), text);
        fingerprints.put(version.id(), fingerprintOf(version, text));
    }
}
