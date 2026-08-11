package etrading;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Consolidates quotes from multiple liquidity providers into a single
 * top-of-book view per symbol, and publishes the best price downstream
 * (pricing engine, client-facing API, risk).
 *
 * See SPEC.md for the behaviour this class is supposed to implement.
 *
 * PRODUCTION CONTEXT
 *   - ~200,000 quotes/sec steady state; bursts over 1,000,000/sec on news.
 *   - Stalling is not acceptable: a stalled aggregator means we show clients
 *     stale prices and we get filled on them.
 */
public class PriceAggregator implements PriceAggregatorApi {

    /** How long a quote stays live, in event time. See SPEC.md rule 2. */
    static final long QUOTE_TTL_NANOS = 2_000_000_000L;

    private static final boolean DEBUG = false;

    private static final SimpleDateFormat TS_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    /** symbol -> (lp -> most recent quote from that lp) */
    private final Map<String, Map<String, Quote>> book = new HashMap<>();

    private final List<QuoteListener> listeners = new ArrayList<>();

    private final BlockingQueue<Quote> inbound = new LinkedBlockingQueue<>();

    private boolean running = true;

    private Thread worker;

    private long quotesProcessed = 0;

    private long quotesPublished = 0;

    @Override
    public void addListener(QuoteListener listener) {
        listeners.add(listener);
    }

    @Override
    public void start() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Quote q = inbound.take();
                        process(q);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        worker.start();
    }

    @Override
    public void onQuote(String symbol, String lp, double bid, double ask, long tsNanos) {
        inbound.offer(new Quote(symbol, lp, bid, ask, tsNanos));
    }

    private void process(Quote q) {
        quotesProcessed++;

        Map<String, Quote> perLp = book.get(q.getSymbol());
        if (perLp == null) {
            perLp = new HashMap<>();
            book.put(q.getSymbol(), perLp);
        }
        perLp.put(q.getLp(), q);

        Quote best = null;
        for (Map.Entry<String, Quote> entry : perLp.entrySet()) {
            Quote candidate = entry.getValue();
            if (best == null || candidate.getBid() > best.getBid()) {
                best = candidate;
            }
        }

        log("[" + TS_FORMAT.format(new Date()) + "] " + q.getSymbol()
                + " best bid " + best.getBid() + " from " + best.getLp()
                + " (" + perLp.size() + " LPs quoting)");

        publish(best);
    }

    private synchronized void publish(Quote best) {
        quotesPublished++;
        for (QuoteListener listener : listeners) {
            listener.onBest(best.getSymbol(), best.getBid(), best.getAsk(),
                    best.getTimestampNanos());
        }
    }

    private void log(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }

    @Override
    public double getBestBid(String symbol) {
        Map<String, Quote> perLp = book.get(symbol);
        if (perLp == null) {
            return Double.NaN;
        }
        double best = Double.NEGATIVE_INFINITY;
        for (Quote q : perLp.values()) {
            if (q.getBid() > best) {
                best = q.getBid();
            }
        }
        return best == Double.NEGATIVE_INFINITY ? Double.NaN : best;
    }

    @Override
    public double getBestAsk(String symbol) {
        Map<String, Quote> perLp = book.get(symbol);
        if (perLp == null) {
            return Double.NaN;
        }
        double best = Double.NEGATIVE_INFINITY;
        for (Quote q : perLp.values()) {
            if (q.getAsk() > best) {
                best = q.getAsk();
            }
        }
        return best == Double.NEGATIVE_INFINITY ? Double.NaN : best;
    }

    @Override
    public boolean isCrossed(String symbol) {
        return getBestBid(symbol) >= getBestAsk(symbol);
    }

    @Override
    public long getQuotesProcessed() {
        return quotesProcessed;
    }

    @Override
    public long getQuotesPublished() {
        return quotesPublished;
    }

    @Override
    public boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    @Override
    public void stop() {
        running = false;
    }
}
