package de.zalando.sprocwrapper.example;

import java.util.List;
import java.util.Optional;

import de.zalando.sprocwrapper.example.transformer.MoneyObjectMapper;

import de.zalando.typemapper.annotations.DatabaseField;
import de.zalando.typemapper.annotations.DatabaseType;

/**
 * @author  jmussler
 */
@DatabaseType(name = "order_type")
public class Order {

    @DatabaseField
    public String orderNumber;

    @DatabaseField(mapper = MoneyObjectMapper.class)
    public OrderMonetaryAmount amount;

    @DatabaseField
    public List<OrderPosition> positions;

    @DatabaseField
    public Optional<AddressPojo> address;

    public Order(final String on, final OrderMonetaryAmount a) {
        this(on, a, null);
    }

    public Order(final String on, final OrderMonetaryAmount a, final AddressPojo address) {
        this.orderNumber = on;
        this.amount = a;
        this.address = Optional.ofNullable(address);
    }

    public Order() {
        this(null, null);
    }
}
