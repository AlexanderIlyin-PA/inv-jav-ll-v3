package part2.accounts;

/**
 * The contract the payment gateway and the admin console code against. Any
 * implementation must honour part 2 of SPEC-part2.md.
 *
 * <p>Every method except {@link #start()} and {@link #stop()} is called
 * concurrently from many request-handling threads.
 */
public interface AccountServiceApi {

    /** Returns the account for this id, creating it once if absent. */
    Account getOrCreate(String id);

    void credit(String id, long amount);

    /** Moves amount from one account to the other. */
    void transfer(String fromId, String toId, long amount);

    long getBalance(String id);

    int getAccountCount();

    /** Total credit/transfer operations applied. */
    long getOperationCount();

    void start();

    /** Must return promptly and the background thread must finish. */
    void stop();

    boolean isRunning();
}
