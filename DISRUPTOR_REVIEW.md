# Part B — code review, discussion only

Nothing to run or compile here. Read it and tell the interviewer what you would
change, and in what order.

This is a real piece of the platform's legacy order pipeline, built on the LMAX
Disruptor. It has been blamed for latency spikes during busy periods, and once
for an order that was risk-checked against the wrong client account.

```java
public class OrderPipeline {

    private volatile OrderEvent lastEvent;

    private final Disruptor<OrderEvent> disruptor = new Disruptor<>(
            OrderEvent::new,
            1000,
            Executors.newCachedThreadPool(),
            ProducerType.MULTI,
            new BlockingWaitStrategy());

    public void init() {
        disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            orderStore.save(event);                 // JDBC insert
            riskEngine.check(event);
            auditLog.write(event.toString());
            lastEvent = event;
        });
        disruptor.start();
    }

    public void submit(Order order) {
        RingBuffer<OrderEvent> ring = disruptor.getRingBuffer();
        long seq = ring.next();
        OrderEvent event = ring.get(seq);
        event.setOrder(order);
        ring.publish(seq);
    }

    public OrderEvent getLastEvent() {
        return lastEvent;
    }
}
```
