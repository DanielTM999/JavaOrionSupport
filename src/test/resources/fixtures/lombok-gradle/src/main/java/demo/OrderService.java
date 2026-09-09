package demo;

public class OrderService {

    public String describe() {
        Order order = Order.builder()
                .reference("A-1")
                .quantity(3)
                .build();
        order.setQuantity(4);
        Order.Line line = new Order.Line("SKU", 2).withSku("OTHER");
        return order.getReference() + line.getSku() + order.getQuantity();
    }
}
