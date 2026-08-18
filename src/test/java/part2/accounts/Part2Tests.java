package part2.accounts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * PART 2 of SPEC-part2.md as a JUnit suite: one test per rule.
 *
 * <p>The assertions themselves live in {@link Part2Checks}, which has no
 * test-framework dependency, so {@code ./gradlew test} and
 * {@code ./gradlew part2} always check the same thing.
 *
 * <p>You should not need to change this file. If you think a test is wrong, say
 * so -- that is a legitimate thing to raise, and SPEC-part2.md is the arbiter.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Part2Tests {

    @Test
    @Order(1)
    @DisplayName("PART2 1: no lost updates")
    void creditsAreNotLost() throws Exception {
        Part2Checks.creditsAreNotLost();
    }

    @Test
    @Order(2)
    @DisplayName("PART2 2: concurrent account creation does not lose accounts")
    void concurrentCreationDoesNotLoseAccounts() throws Exception {
        Part2Checks.concurrentCreationDoesNotLoseAccounts();
    }

    @Test
    @Order(3)
    @DisplayName("PART2 3: getOrCreate creates exactly one account per id")
    void getOrCreateCreatesOneAccountPerId() throws Exception {
        Part2Checks.getOrCreateCreatesOneAccountPerId();
    }

    @Test
    @Order(4)
    @DisplayName("PART2 4: transfer does not deadlock")
    void transferDoesNotDeadlock() throws Exception {
        Part2Checks.transferDoesNotDeadlock();
    }

    @Test
    @Order(5)
    @DisplayName("PART2 5: stop() returns promptly and the background thread finishes")
    void stopIsPromptAndTheThreadFinishes() throws Exception {
        Part2Checks.stopIsPromptAndTheThreadFinishes();
    }
}
