package com.example.amqp.topology;

import org.springframework.amqp.core.TopicExchange;

import java.util.Objects;

/**
 * The three domain-scoped AMQP exchanges (main, DLX, retry) built by {@link DomainTopology}.
 */
public final class DomainExchanges {

    private final TopicExchange main;
    private final TopicExchange dlx;
    private final TopicExchange retry;

    public DomainExchanges(TopicExchange main, TopicExchange dlx, TopicExchange retry) {
        this.main = main;
        this.dlx = dlx;
        this.retry = retry;
    }

    public TopicExchange getMain() {
        return main;
    }

    public TopicExchange getDlx() {
        return dlx;
    }

    public TopicExchange getRetry() {
        return retry;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DomainExchanges that)) {
            return false;
        }
        return Objects.equals(main, that.main)
                && Objects.equals(dlx, that.dlx)
                && Objects.equals(retry, that.retry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(main, dlx, retry);
    }

    @Override
    public String toString() {
        return "DomainExchanges[main=" + main + ", dlx=" + dlx + ", retry=" + retry + "]";
    }
}
