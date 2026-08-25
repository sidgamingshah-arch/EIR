package com.crisil.eir.policy.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The content identity of a policy draft — the thing that makes the impact-preview gate able to
 * tell "previewed" from "previewed, then edited".
 *
 * <p>Every expected digest here comes from GNU {@code sha256sum} on the canonical pre-image, not
 * from running {@link DraftFingerprint}. The exact shell command is quoted at each assertion, so
 * a reader can reproduce the figure without trusting the class under test — and so a change to
 * the canonical form fails these tests rather than silently redefining every stored fingerprint
 * in the audit trail.
 */
class DraftFingerprintTest {

    /**
     * A syntactically valid digest for tests that need one without caring what it hashes.
     * 64 hex characters; the value is arbitrary.
     */
    private static final String SOME_DIGEST =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Nested
    @DisplayName("content identity, derived independently of this class")
    class ContentIdentity {

        @Test
        @DisplayName("the digest is SHA-256 over the length-prefixed canonical form")
        void knownDigest() {
            // The canonical pre-image of of("abc") is "1/3:abc" — one part, then the part's
            // UTF-8 length, a colon and the part. Expected value:
            //   printf '1/3:abc' | sha256sum
            //   0663aff4923fdb5fb5b55146b94efa85f7bfc8eb11e6bf57976958fb4d343150
            assertThat(DraftFingerprint.of("abc").hex())
                .as("SHA-256 of the canonical pre-image '1/3:abc'")
                .isEqualTo("0663aff4923fdb5fb5b55146b94efa85f7bfc8eb11e6bf57976958fb4d343150");
        }

        @Test
        @DisplayName("moving a part boundary changes the fingerprint")
        void lengthPrefixingRemovesConcatenationAmbiguity() {
            // The defect this catches: plain concatenation of the parts would hash
            // "EXPECTED_LIFE_96" + "CPR_12" and "EXPECTED_LIFE_96CPR" + "_12" identically, so a
            // preview of one draft would satisfy the gate for a different draft. Length
            // prefixing makes the boundary part of the pre-image.
            //
            //   printf '2/16:EXPECTED_LIFE_966:CPR_12' | sha256sum
            //   225825798fee918ea04548bf7e85fb6a75b76c903852980d9fe32378efe7eaa3
            //   printf '2/19:EXPECTED_LIFE_96CPR3:_12' | sha256sum
            //   2ae6f867bddbada4d7c10b1e94309632cd27b773500873bddf72837ea6a9d335
            DraftFingerprint split = DraftFingerprint.of("EXPECTED_LIFE_96", "CPR_12");
            DraftFingerprint shifted = DraftFingerprint.of("EXPECTED_LIFE_96CPR", "_12");

            assertThat(split.hex())
                .isEqualTo("225825798fee918ea04548bf7e85fb6a75b76c903852980d9fe32378efe7eaa3");
            assertThat(shifted.hex())
                .isEqualTo("2ae6f867bddbada4d7c10b1e94309632cd27b773500873bddf72837ea6a9d335");
            assertThat(split)
                .as("two drafts that concatenate identically must not fingerprint identically")
                .isNotEqualTo(shifted);
        }

        @Test
        @DisplayName("a draft edit changes the fingerprint; an unchanged draft does not")
        void editSensitivity() {
            // The property the whole gate rests on, asserted directly rather than inferred from
            // the digests above: 03 § 3.6's assumed-life change from 240 months to 96 is a
            // one-token edit that multiplies year-one fee recognition by 3.73x, and it must be
            // visible in the fingerprint.
            DraftFingerprint twentyYears = DraftFingerprint.of("EXPECTED_LIFE_MONTHS=240");
            DraftFingerprint eightYears = DraftFingerprint.of("EXPECTED_LIFE_MONTHS=96");
            assertThat(twentyYears).isNotEqualTo(eightYears);
            assertThat(DraftFingerprint.of("EXPECTED_LIFE_MONTHS=240"))
                .as("the same content fingerprints the same, so a revert restores the match")
                .isEqualTo(twentyYears);
        }

        @Test
        @DisplayName("the varargs and list forms agree")
        void varargsMatchesList() {
            assertThat(DraftFingerprint.of("a", "b"))
                .isEqualTo(DraftFingerprint.of(List.of("a", "b")));
        }

        @Test
        @DisplayName("hex is normalised, so an upper-case digest is the same fingerprint")
        void hexIsNormalised() {
            // Matters because a fingerprint round-trips through persistence and through JSON,
            // and a case difference introduced there would present as a stale-draft refusal on
            // a preview that is not stale — a false refusal is a policy version blocked for no
            // reason, which is how a hard gate gets softened.
            assertThat(new DraftFingerprint(SOME_DIGEST.toUpperCase(Locale.ROOT)))
                .isEqualTo(new DraftFingerprint(SOME_DIGEST));
            assertThat(new DraftFingerprint("  " + SOME_DIGEST + "  "))
                .as("surrounding whitespace from a trimmed column")
                .isEqualTo(new DraftFingerprint(SOME_DIGEST));
        }

        @Test
        @DisplayName("the abbreviation is the leading 12 characters")
        void abbreviated() {
            assertThat(new DraftFingerprint(SOME_DIGEST).abbreviated())
                .isEqualTo("0123456789ab");
            assertThat(new DraftFingerprint(SOME_DIGEST).toString())
                .as("what appears in an audit sentence")
                .isEqualTo("draft 0123456789ab");
        }
    }

    @Nested
    @DisplayName("the shortcuts that would silently disable the gate")
    class RefusedShortcuts {

        @Test
        @DisplayName("a version id is not a fingerprint")
        void versionIdIsRefused() {
            // The single most likely way to defeat this gate without intending to. A version id
            // is stable across exactly the draft edits the fingerprint exists to detect, so a
            // caller passing one turns the staleness check into a check that always passes —
            // and it would never once refuse in production, which reads as a control working.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DraftFingerprint("FEE-2027.1"))
                .withMessageContaining("64-character SHA-256 hex digest")
                .withMessageContaining("pass on every stale preview");
        }

        @Test
        @DisplayName("a digest of the wrong length is refused")
        void wrongLengthIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DraftFingerprint(SOME_DIGEST.substring(1)))
                .withMessageContaining("got 63 characters");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DraftFingerprint(SOME_DIGEST + "0"))
                .withMessageContaining("got 65 characters");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DraftFingerprint(""))
                .withMessageContaining("got 0 characters");
        }

        @Test
        @DisplayName("a non-hexadecimal character is refused, with its position")
        void nonHexIsRefused() {
            // 'g' at index 63. Reported with the position because the usual cause is a
            // truncated or concatenated column, and the position localises it.
            String bad = SOME_DIGEST.substring(0, 63) + "g";
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DraftFingerprint(bad))
                .withMessageContaining("hexadecimal")
                .withMessageContaining("position 63");
        }

        @Test
        @DisplayName("a fingerprint of no content is refused")
        void emptyContentIsRefused() {
            // A digest over nothing is a constant, so every draft with no content parts would
            // fingerprint identically to every other — the always-passes failure again, arrived
            // at from the other direction.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> DraftFingerprint.of(new String[0]))
                .withMessageContaining("a digest over nothing matches every draft");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> DraftFingerprint.of(List.of()))
                .withMessageContaining("needs draft content");
        }

        @Test
        @DisplayName("a null content part is refused, naming its index")
        void nullPartIsRefused() {
            // List.of rejects nulls, so the list has to be built the long way to reach the
            // guard — which is exactly how it would arrive from a mapped row with a null column.
            List<String> withNull = new ArrayList<>();
            withNull.add("EXPECTED_LIFE_MONTHS=96");
            withNull.add(null);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> DraftFingerprint.of(withNull))
                .withMessageContaining("content part 1 is null");
        }

        @Test
        @DisplayName("a null digest is a programming error, not a data condition")
        void nullIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> new DraftFingerprint(null));
        }
    }
}
