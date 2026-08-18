package part1.fuel;

/**
 * One station's prices for one area: a petrol price, a diesel price, and the
 * event-time stamp the station put on them.
 *
 * <p>Immutable, so a report that has been stored can never change underneath the
 * board.
 */
public final class StationReport {

    private final String area;
    private final String station;
    private final double petrol;
    private final double diesel;
    private final long timestamp;

    public StationReport(String area, String station, double petrol, double diesel,
                         long timestamp) {
        this.area = area;
        this.station = station;
        this.petrol = petrol;
        this.diesel = diesel;
        this.timestamp = timestamp;
    }

    public String getArea() {
        return area;
    }

    public String getStation() {
        return station;
    }

    public double getPetrol() {
        return petrol;
    }

    public double getDiesel() {
        return diesel;
    }

    /** Epoch millis, as stamped by the station. Event time, not the wall clock. */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return area + "@" + station + " " + petrol + "/" + diesel;
    }
}
