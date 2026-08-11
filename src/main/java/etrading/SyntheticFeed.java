package etrading;

/**
 * Generates a synthetic quote stream, shared by the spec checks and the load
 * harness.
 *
 * <p>Every LP quotes around a common per-symbol mid that walks up and down in
 * small steps, with a fixed half-spread on either side. The LPs' mids stay far
 * closer together than the spread, so the aggregated book is realistic and
 * <b>never crossed</b>.
 *
 * <p>That last property matters more than it looks. A correct implementation
 * refuses to publish a crossed book (SPEC rule 6), so a feed that produced one
 * would cause a correct aggregator to publish nothing at all -- no latency
 * samples, and an allocation measurement that never exercised the publish path.
 */
final class SyntheticFeed {

    static final String[] SYMBOLS = {
            "EURUSD", "GBPUSD", "USDJPY", "USDCHF",
            "AUDUSD", "USDCAD", "XAUUSD", "XAGUSD"
    };

    static final String[] LPS = {
            "LP-ALPHA", "LP-BRAVO", "LP-CHARLIE",
            "LP-DELTA", "LP-ECHO", "LP-FOXTROT"
    };

    /** Half the spread: five points either side of the mid, so a one-pip spread. */
    private static final double HALF_SPREAD = 0.00005d;

    /**
     * How far the mid moves per step: one point. Across the six LPs the mids
     * therefore span at most five points, against a ten-point spread, so the
     * aggregate book stays uncrossed with a comfortable margin.
     */
    private static final double TICK = 0.00001d;

    private SyntheticFeed() {
    }

    /**
     * Sends quote number {@code i} of the stream. Allocation-free, so it can be
     * used inside an allocation measurement.
     */
    static void send(PriceAggregatorApi aggregator, long i, long tsNanos) {
        int symbolIdx = (int) (i % SYMBOLS.length);
        int lpIdx = (int) ((i / SYMBOLS.length) % LPS.length);

        // Steps this symbol has taken so far. A triangle wave keeps the mid
        // moving in single ticks and never jumps, so consecutive LPs are always
        // within a few ticks of each other.
        long step = i / SYMBOLS.length;
        long phase = step % 200L;
        long triangle = phase < 100L ? phase : 200L - phase;

        double mid = 1.08500d + symbolIdx * 0.01d + triangle * TICK;
        aggregator.onQuote(SYMBOLS[symbolIdx], LPS[lpIdx],
                mid - HALF_SPREAD, mid + HALF_SPREAD, tsNanos);
    }
}
