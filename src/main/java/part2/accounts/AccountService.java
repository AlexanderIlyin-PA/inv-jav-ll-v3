package part2.accounts;

import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory account service: create accounts, credit them, move money
 * between them, and keep a cached total for the admin dashboard.
 *
 * <p>See part 2 of SPEC-part2.md for the behaviour this class is supposed to have. It
 * currently gets all five rules wrong.
 *
 * <p>PRODUCTION CONTEXT
 * <ul>
 *   <li>Every public method except {@link #start()} and {@link #stop()} is
 *       called from the container's request threads -- dozens of them, at the
 *       same time, on the same accounts.</li>
 *   <li>It is deployed behind a rolling restart, so {@link #stop()} has a
 *       shutdown budget measured in hundreds of milliseconds, not seconds.</li>
 *   <li>The symptoms in the field are: totals that do not add up, accounts that
 *       vanish, the occasional duplicate account, request threads that stop
 *       responding under load, and deploys that time out.</li>
 * </ul>
 */
public class AccountService implements AccountServiceApi {

    /** How long the reaper waits between recomputing the cached total. */
    private static final long REAP_INTERVAL_MILLIS = 30_000L;

    private final Map<String, Account> accounts = new HashMap<>();

    private long operationCount = 0;

    private long cachedTotal = 0;

    private boolean running = false;

    private Thread reaper;

    @Override
    public void start() {
        running = true;
        reaper = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(REAP_INTERVAL_MILLIS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    recomputeTotal();
                }
            }
        }, "account-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return reaper != null && reaper.isAlive();
    }

    private void recomputeTotal() {
        long total = 0;
        for (Account account : accounts.values()) {
            total += account.getBalance();
        }
        cachedTotal = total;
    }

    /** What the admin dashboard shows. Refreshed by the reaper thread. */
    public long getCachedTotal() {
        return cachedTotal;
    }

    @Override
    public Account getOrCreate(String id) {
        if (!accounts.containsKey(id)) {
            accounts.put(id, new Account(id));
        }
        return accounts.get(id);
    }

    @Override
    public void credit(String id, long amount) {
        Account account = getOrCreate(id);
        account.add(amount);
        operationCount++;
    }

    @Override
    public void transfer(String fromId, String toId, long amount) {
        Account from = getOrCreate(fromId);
        Account to = getOrCreate(toId);
        synchronized (from) {
            synchronized (to) {
                from.add(-amount);
                to.add(amount);
                operationCount++;
            }
        }
    }

    @Override
    public long getBalance(String id) {
        Account account = accounts.get(id);
        return account == null ? 0L : account.getBalance();
    }

    @Override
    public int getAccountCount() {
        return accounts.size();
    }

    @Override
    public long getOperationCount() {
        return operationCount;
    }
}
