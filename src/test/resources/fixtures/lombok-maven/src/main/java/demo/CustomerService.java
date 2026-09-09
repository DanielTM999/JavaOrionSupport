package demo;

public class CustomerService {

    public String describe() {
        Customer customer = Customer.builder()
                .name("Ana")
                .document("123")
                .build();
        customer.setLoyaltyPoints(10);
        return customer.getName() + customer.getDocument() + customer.getLoyaltyPoints();
    }
}
