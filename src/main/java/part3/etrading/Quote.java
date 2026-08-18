package part3.etrading;

/**
 * A single quote from a single liquidity provider for a single symbol.
 */
public final class Quote {

    private final String symbol;
    private final String lp;
    private final double bid;
    private final double ask;
    private final long timestampNanos;

    public Quote(String symbol, String lp, double bid, double ask, long timestampNanos) {
        this.symbol = symbol;
        this.lp = lp;
        this.bid = bid;
        this.ask = ask;
        this.timestampNanos = timestampNanos;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getLp() {
        return lp;
    }

    public double getBid() {
        return bid;
    }

    public double getAsk() {
        return ask;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    @Override
    public String toString() {
        return symbol + "@" + lp + " " + bid + "/" + ask;
    }
}
