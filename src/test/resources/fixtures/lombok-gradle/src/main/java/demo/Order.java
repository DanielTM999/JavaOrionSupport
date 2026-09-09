package demo;

import lombok.Data;
import lombok.Value;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@SuperBuilder
@Accessors(chain = true)
public class Order {

    private String reference;
    private int quantity;

    @Value
    public static class Line {
        @With
        String sku;
        int amount;
    }

    public void audit() {
        log.debug("pedido {} com {} item(ns)", reference, quantity);
    }
}
