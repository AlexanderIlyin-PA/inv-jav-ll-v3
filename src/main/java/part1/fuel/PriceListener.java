package part1.fuel;

/**
 * Downstream consumer of cheapest-price updates: the forecourt map, the
 * price-comparison API, the alerting service.
 *
 * <p>Implementations run on whichever thread called
 * {@link PriceBoardApi#onReport} and must be quick.
 */
public interface PriceListener {

    /**
     * @param area           the area the update is for, e.g. {@code "Camden"}
     * @param cheapestPetrol cheapest live petrol price across all reporting stations
     * @param cheapestDiesel cheapest live diesel price across all reporting stations
     * @param timestamp      epoch-millis timestamp of the report that caused this update
     */
    void onBest(String area, double cheapestPetrol, double cheapestDiesel, long timestamp);
}
