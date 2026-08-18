package part1.fuel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * PART 1 of SPEC-part1.md as a JUnit suite: one test per rule.
 *
 * <p>The assertions themselves live in {@link Part1Checks}, which has no
 * test-framework dependency, so {@code ./gradlew test} and
 * {@code ./gradlew part1} always check the same thing.
 *
 * <p>You should not need to change this file. If you think a test is wrong, say
 * so -- that is a legitimate thing to raise, and SPEC-part1.md is the arbiter.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Part1Tests {

    @Test
    @Order(1)
    @DisplayName("PART1 1: the published cheapest of each is correct")
    void publishedCheapestIsCorrect() throws Exception {
        Part1Checks.publishedCheapestIsCorrect();
    }

    @Test
    @Order(2)
    @DisplayName("PART1 2: out-of-order reports are ignored")
    void outOfOrderReportsAreIgnored() throws Exception {
        Part1Checks.outOfOrderReportsAreIgnored();
    }

    @Test
    @Order(3)
    @DisplayName("PART1 3: stale reports expire")
    void staleReportsExpire() throws Exception {
        Part1Checks.staleReportsExpire();
    }

    @Test
    @Order(4)
    @DisplayName("PART1 4: an update is published exactly when the cheapest changes")
    void publishesExactlyWhenCheapestChanges() throws Exception {
        Part1Checks.publishesExactlyWhenCheapestChanges();
    }
}
