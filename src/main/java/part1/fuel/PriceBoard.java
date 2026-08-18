package part1.fuel;

import java.util.ArrayList;
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
 * <p>Stations push prices over a webhook and the HTTP layer calls
 * {@link #onReport}. The forecourt map reads the getters between calls: a wrong
 * price shown to a driver is a wrong price they drive across town for.
 */
public class PriceBoard implements PriceBoardApi {

    /**
     * How long a station's report stays live, in event time (epoch millis).
     * See part 1 rule 3 in SPEC-part1.md.
     */
    static final long REPORT_TTL_MILLIS = 60L * 60_000L;

    /** area ->; (station ->; that station's most recent report) */
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

        publish(area, cheapest.getPetrol(), cheapest.getDiesel(), timestamp);
        // ^ both halves of the published pair come off one report
    }

    /** Sends one cheapest-price update downstream. Takes the pair, not a report. */
    private void publish(String area, double cheapestPetrol, double cheapestDiesel,
                         long timestamp) {
        updatesPublished++;
        for (PriceListener listener : listeners) {
            listener.onBest(area, cheapestPetrol, cheapestDiesel, timestamp);
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
        double cheapest = Double.POSITIVE_INFINITY;
        for (StationReport report : perStation.values()) {
            if (report.getDiesel() < cheapest) {
                cheapest = report.getDiesel();
            }
        }
        return cheapest == Double.POSITIVE_INFINITY ? Double.NaN : cheapest;
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
