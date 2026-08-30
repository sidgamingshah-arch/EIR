package com.crisil.eir.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two properties of {@link Json} that a control report depends on: a figure never crosses the
 * wire as a JSON number, and a key is never written twice.
 *
 * <p>Both are about the same failure — a figure that silently is not the figure the engine computed
 * — arriving by two different routes. The first is a precision loss in the reader; the second is a
 * value discarded in the writer.
 */
class JsonTest {

    @Nested
    @DisplayName("a duplicate key is refused rather than silently replaced")
    class DuplicateKeys {

        @Test
        @DisplayName("setting one key twice throws, naming both values")
        void aDuplicateKeyThrows() {
            // The defect this closes, found in a review of the transition endpoints: a response
            // assembled cohortCount in one branch and again in another, LinkedHashMap.put took the
            // second silently, and the endpoint published a count it had computed and discarded.
            // Invisible to every assertion on the surviving value -- which is why the writer has to
            // refuse rather than the reader having to notice.
            assertThatThrownBy(() -> Json.object()
                .count("cohortCount", 7)
                .count("cohortCount", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cohortCount")
                .as("both values must be named: which one was about to be lost is the whole"
                    + " diagnostic")
                .hasMessageContaining("7")
                .hasMessageContaining("3");
        }

        @Test
        @DisplayName("it throws across differing field types, not only for a repeated writer")
        void aDuplicateAcrossTypesThrows() {
            // The realistic shape of the defect: two branches disagree about what the field IS, so
            // the duplicate arrives through two different methods and a per-method guard would miss
            // it. Every writer goes through one private setter for exactly this reason.
            assertThatThrownBy(() -> Json.object()
                .figure("deviation", new BigDecimal("41358.04"))
                .str("deviation", "nil"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deviation");

            assertThatThrownBy(() -> Json.object()
                .strings("reasons", List.of("EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED"))
                .array("reasons", List.of(Json.object())))
                .isInstanceOf(IllegalStateException.class);

            assertThatThrownBy(() -> Json.object()
                .bool("mayClose", false)
                .obj("mayClose", Json.object()))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a null value still claims the key, so a later write cannot slip past")
        void aNullValuedFieldStillClaimsTheKey() {
            // A null renders as JSON null and is a real answer -- "this figure does not exist" --
            // not an empty slot. Treating it as absent would leave the one route by which a figure
            // could still overwrite a stated nothing, which is the more misleading direction: the
            // reader was told the field was null.
            assertThatThrownBy(() -> Json.object()
                .figure("eir", null)
                .figure("eir", new BigDecimal("0.010421491800")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eir");
        }

        @Test
        @DisplayName("distinct keys are unaffected, and insertion order survives")
        void distinctKeysKeepTheirOrder() {
            // Order is load-bearing: assertions elsewhere anchor on the sequence of fields in a
            // response body, so the refusal must not have been bought by changing the map.
            String body = Json.object()
                .str("contractId", "C-0001")
                .count("periodId", 202805)
                .figure("closingGca", new BigDecimal("486840.64"))
                .bool("mayClose", true)
                .toString();

            assertThat(body).isEqualTo("{\"contractId\":\"C-0001\",\"periodId\":202805,"
                + "\"closingGca\":\"486840.64\",\"mayClose\":true}");
        }
    }

    @Nested
    @DisplayName("a figure crosses the wire as a string")
    class FiguresAreStrings {

        @Test
        @DisplayName("reference case 1's EIR keeps its twelve places")
        void theRateKeepsItsScale() {
            // 0.010421491800 through a JSON number is a double in every browser that will read it,
            // and the trailing zeros that say the rate is stated to twelve places are gone.
            assertThat(Json.object()
                .figure("eir", new BigDecimal("0.010421491800"))
                .toString())
                .isEqualTo("{\"eir\":\"0.010421491800\"}");
        }

        @Test
        @DisplayName("a count is the one thing emitted as a JSON number")
        void aCountIsANumber() {
            assertThat(Json.object().count("contracts", 3).toString())
                .isEqualTo("{\"contracts\":3}");
        }
    }
}
