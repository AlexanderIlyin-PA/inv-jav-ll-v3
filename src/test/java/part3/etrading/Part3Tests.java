package part3.etrading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * PART 3 of SPEC-part3.md as a JUnit suite: one test per rule.
 *
 * <p>The assertions themselves live in {@link Part3Checks}, which has no
 * test-framework dependency, so {@code ./gradlew test} and
 * {@code ./gradlew part3} always check the same thing.
 *
 * <p>You should not need to change this file. If you think a test is wrong, say
 * so -- that is a legitimate thing to raise, and SPEC-part3.md is the arbiter.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Part3Tests {

    @Test
    @Order(1)
    @DisplayName("PART3 1: hot path allocates under 40 bytes per quote")
    void hotPathAllocatesUnder40BytesPerQuote() throws Exception {
        Part3Checks.hotPathAllocatesUnder40BytesPerQuote();
    }

    @Test
    @Order(2)
    @DisplayName("PART3 2: hot path allocates almost nothing (under 16 bytes/quote)")
    void hotPathAllocatesAlmostNothing() throws Exception {
        Part3Checks.hotPathAllocatesAlmostNothing();
    }
}
