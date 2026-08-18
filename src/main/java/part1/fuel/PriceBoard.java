package part1.fuel;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the cheapest petrol and the cheapest diesel per area out of everything
 * the stations are currently reporting, and tells subscribers whenever either
 * moves.
 *
 * <p>See part 1 of SPEC-part1.md for the behaviour this class is supposed to
 * implement. It currently gets most of it wrong.
 *
 * <p>PRODUCTION CONTEXT
 * <ul>
 *   <li>Stations push prices over a webhook; the HTTP layer calls
 *       {@link #onReport} and there is exactly one such caller, so this class is
 *       single-threaded by construction. It does not need a queue, a worker or a
 *       lifecycle, and adding one would be a step backwards.</li>
 *   <li>The forecourt map reads the getters between calls; a wrong price shown to
 *       a driver is a wrong price they drive across town for.</li>
 * </ul>
 */
public class PriceBoard implements PriceBoardApi {

    /**
     * How long a station's report stays live, in event time (epoch millis).
     * See part 1 rule 3 in SPEC-part1.md.
     */
    static final long REPORT_TTL_MILLIS = 60L * 60_000L;

    private static final boolean DEBUG = false;

    /** area -&gt; (station -&gt; that station's most recent report) */
    private final Map<String, Map<String, StationReport>> board = new HashMap<>();

    private final List<PriceListener> listeners = new ArrayList<>();

    private long reportsAccepted = 0;

    private long updatesPublished = 0;

    @Override
    public void addListener(PriceListener listener) {
        listeners.add(listener);
    }

    @Override
    public void onReport(String area, String station, double petrol, double diesel,
                         long timestamp) {
        Map<String, StationReport> perStation = board.get(area);
        if (perStation == null) {
            perStation = new HashMap<>();
            board.put(area, perStation);
        }
        perStation.put(station,
                new StationReport(area, station, petrol, diesel, timestamp));
        reportsAccepted++;

        StationReport cheapest = null;
        for (Map.Entry<String, StationReport> entry : perStation.entrySet()) {
            StationReport candidate = entry.getValue();
            if (cheapest == null || candidate.getPetrol() < cheapest.getPetrol()) {
                cheapest = candidate;
            }
        }

        log(String.format("[%tT] %s cheapest %.3f petrol / %.3f diesel from %s "
                        + "(%d stations reporting)",
                new Date(), area, cheapest.getPetrol(), cheapest.getDiesel(),
                cheapest.getStation(), perStation.size()));

        publish(cheapest.getArea(), cheapest.getPetrol(), cheapest.getDiesel(),
                cheapest.getTimestamp());
    }

    /** Sends one cheapest-price update downstream. Takes the pair, not a report. */
    private void publish(String area, double cheapestPetrol, double cheapestDiesel,
                         long timestamp) {
        updatesPublished++;
        for (PriceListener listener : listeners) {
            listener.onBest(area, cheapestPetrol, cheapestDiesel, timestamp);
        }
    }

    private void log(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }

    @Override
    public double getCheapestPetrol(String area) {
        Map<String, StationReport> perStation = board.get(area);
        if (perStation == null) {
            return Double.NaN;
        }
        double cheapest = Double.POSITIVE_INFINITY;
        for (StationReport report : perStation.values()) {
            if (report.getPetrol() < cheapest) {
                cheapest = report.getPetrol();
            }
        }
        return cheapest == Double.POSITIVE_INFINITY ? Double.NaN : cheapest;
    }

    @Override
    public double getCheapestDiesel(String area) {
        Map<String, StationReport> perStation = board.get(area);
        if (perStation == null) {
            return Double.NaN;
        }
        StationReport cheapest = null;
        for (StationReport report : perStation.values()) {
            if (cheapest == null || report.getPetrol() < cheapest.getPetrol()) {
                cheapest = report;
            }
        }
        return cheapest == null ? Double.NaN : cheapest.getDiesel();
    }

    @Override
    public long getReportsAccepted() {
        return reportsAccepted;
    }

    @Override
    public long getUpdatesPublished() {
        return updatesPublished;
    }
}
