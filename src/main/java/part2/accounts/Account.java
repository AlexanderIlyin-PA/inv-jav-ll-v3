package part2.accounts;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One account: an id and a balance in minor units.
 */
public final class Account {

    /**
     * How many {@code Account} objects have ever been constructed.
     *
     * <p>Instrumentation for the checks: part 2 rule 3 reads it to prove that
     * {@code getOrCreate} creates exactly one account per id, however many
     * threads ask for it at once. Please leave the counter where it is, and
     * please keep constructing exactly one {@code Account} per account.
     */
    private static final AtomicLong CONSTRUCTED = new AtomicLong();

    public static long constructionCount() {
        return CONSTRUCTED.get();
    }

    /**
     * Instrumentation for the checks, and nothing else: part 2 rule 3 installs a
     * hook here that holds the first thread which constructs an {@code Account}
     * inside {@code getOrCreate} until another thread has reached the same point.
     * That is what makes rule 3 a deterministic check rather than a race that may
     * or may not happen on the day.
     *
     * <p>Nothing in production sets it. Please leave the hook and the counter
     * where they are, and keep both calls in the constructor.
     */
    static volatile Runnable constructionHook;

    private final String id;

    private long balance;

    public Account(String id) {
        this.id = id;
        CONSTRUCTED.incrementAndGet();
        Runnable hook = constructionHook;
        if (hook != null) {
            hook.run();
        }
    }

    public String getId() {
        return id;
    }

    public long getBalance() {
        return balance;
    }

    /** Applies a signed delta to the balance. */
    void add(long amount) {
        balance += amount;
    }

    @Override
    public String toString() {
        return id + "=" + balance;
    }
}
