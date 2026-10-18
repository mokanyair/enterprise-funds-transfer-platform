package com.enterprise.funds.transfer.config;

import com.enterprise.funds.transfer.notifications.MalformedEventException;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Kafka wiring beyond Spring Boot's defaults: dead-lettering for the consumer, optional topic creation (E4, E5). */
@Configuration
public class KafkaEventConfig {

    /** Dead-letter topic of a domain topic. Same partition count, so the record keeps its partition. */
    public static String dltOf(String topic) {
        return topic + ".DLT";
    }

    /**
     * Retries a failing record maxAttempts times in total with a fixed back-off, then publishes it to the topic's
     * DLT and moves on, so one bad event never blocks its partition. Malformed events skip the retries.
     * Spring Boot applies this handler to the auto-configured listener container factory.
     */
    @Bean
    @ConditionalOnProperty(prefix = "funds.notifications", name = "consumer-enabled", havingValue = "true")
    CommonErrorHandler notificationErrorHandler(KafkaTemplate<String, String> kafka, FundsProperties props) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, e) -> new TopicPartition(dltOf(record.topic()), record.partition()));
        var n = props.notifications();
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(n.retryBackoff().toMillis(), n.maxAttempts() - 1L));
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }

    /** Only for a single-broker dev box (funds.kafka.create-topics). Shared clusters provision topics themselves. */
    @Bean
    @ConditionalOnProperty(prefix = "funds.kafka", name = "create-topics", havingValue = "true")
    KafkaAdmin.NewTopics fundsTopics(FundsProperties props) {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : props.outbox().allTopics()) {
            for (String name : List.of(topic, dltOf(topic))) {
                topics.add(TopicBuilder.name(name).partitions(props.kafka().partitions())
                        .replicas(props.kafka().replicationFactor()).build());
            }
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
