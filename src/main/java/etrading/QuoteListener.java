package etrading;

/**
 * Downstream consumer of consolidated top-of-book updates.
 * Implementations run on the aggregator's publishing thread and must be fast.
 */
public interface QuoteListener {

    /**
     * @param symbol        instrument the update is for
     * @param bestBid       best (highest) bid across all quoting LPs
     * @param bestAsk       best (lowest) ask across all quoting LPs
     * @param eventTsNanos  System.nanoTime() stamp of the quote that triggered this update
     */
    void onBest(String symbol, double bestBid, double bestAsk, long eventTsNanos);
}
