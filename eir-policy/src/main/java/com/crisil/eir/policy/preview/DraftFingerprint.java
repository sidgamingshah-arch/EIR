package com.crisil.eir.policy.preview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The content identity of the policy draft an {@link ImpactPreview} was computed against —
 * a SHA-256 digest over the draft's own content, not its version id.
 *
 * <p><b>Why the version id will not do.</b> A draft is edited under a stable id: {@code
 * FEE-2027.1} is {@code FEE-2027.1} through every revision until it goes effective, which is
 * what makes it citable by a computation. So a preview keyed only on the id claims to preview
 * whatever that id currently says, and the claim silently stops being true the moment somebody
 * edits the draft. Matching on content is the only match that survives an edit, and 03 § 3.6 is
 * the reason it has to: shifting one assumed-life input moves year-one fee recognition 3.73x, so
 * "the draft changed a little" and "the preview is wrong by a factor of nearly four" are the
 * same sentence.
 *
 * <p><b>The 64-hex-character constraint is load-bearing, not tidiness.</b> The obvious way to
 * defeat this gate without meaning to is to pass something convenient where a fingerprint is
 * wanted — the version id, a description, a timestamp string, an empty string. Every one of
 * those is stable across the draft edits the fingerprint exists to detect, so every one of them
 * turns the staleness check into a check that always passes. Refusing anything that is not a
 * 256-bit hex digest makes the shortcut fail at construction rather than in production, where it
 * would present as a preview gate that never once refused.
 *
 * <p>Digests are compared, never inverted, so no draft content is recoverable from a stored
 * fingerprint. That matters for a record which is retained for the life of the audit trail.
 *
 * @param hex the digest, lower-case, exactly 64 hexadecimal characters
 */
public record DraftFingerprint(String hex) {

    /** SHA-256 in hex. */
    private static final int HEX_LENGTH = 64;

    /** How much of the digest appears in an audit sentence. Collision-safe for reading. */
    private static final int ABBREVIATED_LENGTH = 12;

    public DraftFingerprint {
        Objects.requireNonNull(hex, "hex");
        hex = hex.trim().toLowerCase(Locale.ROOT);
        if (hex.length() != HEX_LENGTH) {
            throw new IllegalArgumentException(
                "a draft fingerprint is a 64-character SHA-256 hex digest, got " + hex.length()
                    + " characters ('" + hex + "'). A version id or a description here would make"
                    + " the staleness check pass on every stale preview");
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexDigit) {
                throw new IllegalArgumentException(
                    "a draft fingerprint is hexadecimal, got '" + c + "' at position " + i);
            }
        }
    }

    /**
     * Fingerprints a draft from its content parts.
     *
     * <p>Parts are <b>length-prefixed</b> before hashing, and the part count is prefixed too.
     * Plain concatenation is ambiguous, and the ambiguity is not theoretical for this input:
     * {@code of("EXPECTED_LIFE_96", "CPR_12")} and {@code of("EXPECTED_LIFE_96CPR", "_12")}
     * concatenate identically, so two different drafts would fingerprint the same and a preview
     * of one would satisfy the gate for the other. The canonical pre-image is
     * {@code count + "/" + (utf8Length + ":" + part)...}, so a part boundary cannot be moved
     * without changing a prefix.
     *
     * @throws IllegalArgumentException if no parts are supplied — a fingerprint of nothing
     *     identifies nothing, and would identify every empty draft as the same draft
     */
    public static DraftFingerprint of(List<String> contentParts) {
        Objects.requireNonNull(contentParts, "contentParts");
        if (contentParts.isEmpty()) {
            throw new IllegalArgumentException(
                "a draft fingerprint needs draft content; a digest over nothing matches every"
                    + " draft that also has none");
        }
        StringBuilder canonical = new StringBuilder();
        canonical.append(contentParts.size()).append('/');
        for (int i = 0; i < contentParts.size(); i++) {
            String part = contentParts.get(i);
            if (part == null) {
                throw new IllegalArgumentException("content part " + i + " is null");
            }
            byte[] utf8 = part.getBytes(StandardCharsets.UTF_8);
            canonical.append(utf8.length).append(':').append(part);
        }
        return new DraftFingerprint(sha256Hex(canonical.toString()));
    }

    /** Convenience overload; see {@link #of(List)} for the canonical pre-image. */
    public static DraftFingerprint of(String... contentParts) {
        Objects.requireNonNull(contentParts, "contentParts");
        return of(List.of(contentParts));
    }

    private static String sha256Hex(String canonical) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every conformant Java runtime provides SHA-256. Unreachable, and rethrown rather
            // than swallowed because the alternative — degrading to a weaker digest — would
            // silently weaken the one check that detects an edited draft.
            throw new IllegalStateException("SHA-256 unavailable in this runtime", e);
        }
        byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(HEX_LENGTH);
        for (byte b : hash) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** The leading {@value #ABBREVIATED_LENGTH} characters, for an audit sentence. */
    public String abbreviated() {
        return hex.substring(0, ABBREVIATED_LENGTH);
    }

    @Override
    public String toString() {
        return "draft " + abbreviated();
    }
}
