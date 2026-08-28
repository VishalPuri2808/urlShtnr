package com.urlshortener.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

// Declares the click-events topic; Kafka creates it on startup if absent
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic urlClickEventsTopic(
            @Value("${app.kafka.topic.click-events}") String topicName) {
        // 3 partitions allow parallel consumers; 1 replica suits a single-broker dev setup
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Explicit typed template so UrlClickEventProducer can inject KafkaTemplate<String, Object>.
     * Spring Boot's @ConditionalOnMissingBean(KafkaTemplate.class) skips the auto-configured
     * template when this bean is present.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(KafkaProperties kafkaProperties) {
        return new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null)));
    }
}
