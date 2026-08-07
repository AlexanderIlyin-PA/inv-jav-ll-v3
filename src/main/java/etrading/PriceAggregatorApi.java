package etrading;

/**
 * The contract the pricing engine and the LP session layer code against.
 * Any implementation must honour SPEC.md.
 */
public interface PriceAggregatorApi {

    void addListener(QuoteListener listener);

    void start();

    /** Called from each LP session thread when a new quote arrives off the wire. */
    void onQuote(String symbol, String lp, double bid, double ask, long tsNanos);

    /** Highest live bid across all liquidity providers. NaN if none. */
    double getBestBid(String symbol);

    /** Lowest live ask across all liquidity providers. NaN if none. */
    double getBestAsk(String symbol);

    /** True when the aggregated top of book is crossed (best bid >= best ask). */
    boolean isCrossed(String symbol);

    long getQuotesProcessed();

    long getQuotesPublished();

    /** True while the aggregator's worker thread is alive. */
    boolean isRunning();

    void stop();
}
