package part3.etrading;

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
 * <p>See part 3 of SPEC-part3.md for what is being asked of this class. Part 3 is
 * about one thing only: <b>how much this hot path allocates per quote</b>.
 * Nothing else here is graded, so do not spend the time on the aggregation
 * logic.
 *
 * <p>PRODUCTION CONTEXT
 * <ul>
 *   <li>~200,000 quotes/sec steady state; bursts over 1,000,000/sec on news.</li>
 *   <li>The pauses show up as GC, and the GC is fed from here.</li>
 *   <li>Stalling is not acceptable: a stalled aggregator means we show clients
 *       stale prices and we get filled on them.</li>
 * </ul>
 */
public class PriceAggregator implements PriceAggregatorApi {

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
        worker.setDaemon(true);
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

        publish(best.getSymbol(), best.getBid(), best.getAsk(),
                best.getTimestampNanos());
    }

    /** Sends one top-of-book update downstream. Takes the pair, not a quote. */
    private synchronized void publish(String symbol, double bestBid, double bestAsk,
                                      long eventTsNanos) {
        quotesPublished++;
        for (QuoteListener listener : listeners) {
            listener.onBest(symbol, bestBid, bestAsk, eventTsNanos);
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
        double best = Double.POSITIVE_INFINITY;
        for (Quote q : perLp.values()) {
            if (q.getAsk() < best) {
                best = q.getAsk();
            }
        }
        return best == Double.POSITIVE_INFINITY ? Double.NaN : best;
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
