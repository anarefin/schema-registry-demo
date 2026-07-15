package com.example.producer;

import com.example.contracts.customers.topology.CustomerPublisherTopology;
import com.example.contracts.orders.topology.OrderPublisherTopology;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Producer service entry point — the sole publisher of both the orders and customers domains.
 *
 * <p>As the single publisher, it owns those domains' exchanges: it {@code @Import}s both
 * {@code *PublisherTopology} configurations so all six exchanges (main/DLX/retry per domain) are
 * declared at startup. Exchange ownership is opt-in via this import — the contracts jars alone no
 * longer auto-declare exchanges. Consumers bind their private queues to these publisher-owned
 * exchanges.
 */
@SpringBootApplication(excludeName = "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration")
@Import({OrderPublisherTopology.class, CustomerPublisherTopology.class})
public class ProducerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProducerApplication.class, args);
    }
}
