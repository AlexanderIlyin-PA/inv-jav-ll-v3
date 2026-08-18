package part1.fuel;

/**
 * The contract the forecourt map and the station integration layer code against.
 * Any implementation must honour part 1 of SPEC-part1.md.
 *
 * <p>There is no {@code start()} or {@code stop()} and no background thread:
 * {@link #onReport} does its work on the calling thread, and every getter reads
 * state that {@code onReport} has already finished writing.
 */
public interface PriceBoardApi {

    void addListener(PriceListener listener);

    /** A station's current prices for an area. Called on the caller's thread. */
    void onReport(String area, String station, double petrol, double diesel,
                  long timestamp);

    /** Cheapest live petrol price in the area, or NaN if none. */
    double getCheapestPetrol(String area);

    /** Cheapest live diesel price in the area, or NaN if none. */
    double getCheapestDiesel(String area);

    /** Reports accepted, i.e. offered to {@code onReport} and not discarded. */
    long getReportsAccepted();

    /** Updates delivered to listeners. */
    long getUpdatesPublished();
}
