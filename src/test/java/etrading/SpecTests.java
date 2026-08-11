package etrading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * SPEC.md as a JUnit suite: one test per rule.
 *
 * <p>The assertions themselves live in {@link SpecChecks}, which has no
 * test-framework dependency. This class only names, orders and tags them, so
 * {@code ./gradlew test} and {@code ./gradlew spec} always check the same thing.
 *
 * <p>You should not need to change this file. If you think a test is wrong,
 * say so — that is a legitimate thing to raise, and SPEC.md is the arbiter.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpecTests {

    @Test
    @Order(1)
    @Tag("core")
    @DisplayName("CORE 1: the published top of book is correct")
    void publishedTopOfBookIsCorrect() throws Exception {
        SpecChecks.publishedTopOfBookIsCorrect();
    }

    @Test
    @Order(2)
    @Tag("core")
    @DisplayName("CORE 2: quotes that must not count are excluded")
    void quotesThatMustNotCountAreExcluded() throws Exception {
        SpecChecks.quotesThatMustNotCountAreExcluded();
    }

    @Test
    @Order(3)
    @Tag("core")
    @DisplayName("CORE 3: an update is published exactly when top of book changes")
    void publishesExactlyWhenTopOfBookChanges() throws Exception {
        SpecChecks.publishesExactlyWhenTopOfBookChanges();
    }

    @Test
    @Order(4)
    @Tag("core")
    @DisplayName("CORE 4: safe under concurrent producers and consumers, and it stops")
    void safeUnderConcurrentProducersAndConsumers() throws Exception {
        SpecChecks.safeUnderConcurrentProducersAndConsumers();
    }

    @Test
    @Order(5)
    @Tag("core")
    @DisplayName("CORE 5: hot path allocates under 40 bytes per quote")
    void hotPathAllocatesUnder40BytesPerQuote() throws Exception {
        SpecChecks.hotPathAllocatesUnder40BytesPerQuote();
    }

    @Test
    @Order(6)
    @Tag("stretch")
    @DisplayName("STRETCH 6: a crossed book is never published")
    void crossedBookIsNeverPublished() throws Exception {
        SpecChecks.crossedBookIsNeverPublished();
    }

    @Test
    @Order(7)
    @Tag("stretch")
    @DisplayName("STRETCH 7: hot path allocates almost nothing (under 16 bytes/quote)")
    void hotPathAllocatesAlmostNothing() throws Exception {
        SpecChecks.hotPathAllocatesAlmostNothing();
    }
}
