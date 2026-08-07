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
    @DisplayName("CORE 1: best bid is the highest bid across all LPs")
    void bestBidIsHighestAcrossLps() throws Exception {
        SpecChecks.bestBidIsHighestAcrossLps();
    }

    @Test
    @Order(2)
    @Tag("core")
    @DisplayName("CORE 2: best ask is the lowest ask across all LPs")
    void bestAskIsLowestAcrossLps() throws Exception {
        SpecChecks.bestAskIsLowestAcrossLps();
    }

    @Test
    @Order(3)
    @Tag("core")
    @DisplayName("CORE 3: out-of-order quotes are ignored")
    void outOfOrderQuotesAreIgnored() throws Exception {
        SpecChecks.outOfOrderQuotesAreIgnored();
    }

    @Test
    @Order(4)
    @Tag("core")
    @DisplayName("CORE 4: stale quotes expire out of top of book")
    void staleQuotesExpire() throws Exception {
        SpecChecks.staleQuotesExpire();
    }

    @Test
    @Order(5)
    @Tag("core")
    @DisplayName("CORE 5: updates are published only when top of book changes")
    void publishesOnlyWhenTopOfBookChanges() throws Exception {
        SpecChecks.publishesOnlyWhenTopOfBookChanges();
    }

    @Test
    @Order(6)
    @Tag("core")
    @DisplayName("CORE 6: stop() terminates the worker thread")
    void stopTerminatesTheWorkerThread() throws Exception {
        SpecChecks.stopTerminatesTheWorkerThread();
    }

    @Test
    @Order(7)
    @Tag("core")
    @DisplayName("CORE 7: hot path allocates under 64 bytes per quote")
    void hotPathAllocatesUnder64BytesPerQuote() throws Exception {
        SpecChecks.hotPathAllocatesUnder64BytesPerQuote();
    }

    @Test
    @Order(8)
    @Tag("stretch")
    @DisplayName("STRETCH 8: a crossed book is detected and not published")
    void crossedBookIsDetectedAndNotPublished() throws Exception {
        SpecChecks.crossedBookIsDetectedAndNotPublished();
    }

    @Test
    @Order(9)
    @Tag("stretch")
    @DisplayName("STRETCH 9: hot path allocates essentially nothing")
    void hotPathAllocatesEssentiallyNothing() throws Exception {
        SpecChecks.hotPathAllocatesEssentiallyNothing();
    }
}
