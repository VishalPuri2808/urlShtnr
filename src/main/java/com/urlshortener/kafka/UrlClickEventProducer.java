package com.urlshortener.kafka;

import com.urlshortener.config.AppProperties;
import com.urlshortener.event.UrlClickedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Fire-and-forget publisher: returns immediately after enqueuing the event to
 * Kafka's producer buffer; the redirect response is never held waiting for an ack.
 */
@Component
public class UrlClickEventProducer {

    private static final Logger log = LoggerFactory.getLogger(UrlClickEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppProperties                 appProperties;

    public UrlClickEventProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                 AppProperties appProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.appProperties = appProperties;
    }

    public void publish(UrlClickedEvent event) {
        String topic = appProperties.getKafka().getTopic().getClickEvents();
        // Use shortCode as partition key so all clicks for the same URL land on one partition
        kafkaTemplate.send(topic, event.shortCode(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish UrlClickedEvent for '{}': {}",
                                  event.shortCode(), ex.getMessage());
                    }
                });
    }
}
