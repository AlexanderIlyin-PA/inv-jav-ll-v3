package part3.etrading;

/**
 * The contract the pricing engine and the LP session layer code against.
 * Any implementation must honour part 3 of SPEC-part3.md.
 */
public interface PriceAggregatorApi {

    void addListener(QuoteListener listener);

    void start();

    /** Called from each LP session thread when a new quote arrives off the wire. */
    void onQuote(String symbol, String lp, double bid, double ask, long tsNanos);

    /** Highest bid across all liquidity providers. NaN if none. */
    double getBestBid(String symbol);

    /** Lowest ask across all liquidity providers. NaN if none. */
    double getBestAsk(String symbol);

    /** Quotes that reached processing. Nothing may be dropped. */
    long getQuotesProcessed();

    long getQuotesPublished();

    /** True while the aggregator's worker thread is alive. */
    boolean isRunning();

    void stop();
}
