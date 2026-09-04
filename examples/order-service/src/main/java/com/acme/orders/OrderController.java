package com.acme.orders;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An ordinary CRUD controller.
 *
 * Read it looking for tenancy and there is none: no tenant parameter, no header read, no
 * filter passed to the repository, no check that a fetched order belongs to the caller.
 * That is the point. Isolation is applied underneath by the connection and the database.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orders;
    private final OrderSummaryService summaries;

    public OrderController(OrderRepository orders, OrderSummaryService summaries) {
        this.orders = orders;
        this.summaries = summaries;
    }

    public record PlaceOrder(String customer, String item, long amountCents) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order place(@RequestBody PlaceOrder request) {
        return orders.save(new Order(request.customer(), request.item(), request.amountCents()));
    }

    @GetMapping
    public List<Order> list() {
        return orders.findAll();
    }

    /** Computed on an @Async worker thread. Still no tenant anywhere in this method. */
    @GetMapping("/summary")
    public OrderSummary summary() throws Exception {
        return summaries.computeAsync().get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> byId(@PathVariable Long id) {
        return orders.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
