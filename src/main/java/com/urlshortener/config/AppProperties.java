package com.urlshortener.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds all app.* YAML properties; nested static classes mirror YAML sub-trees
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String baseUrl = "http://localhost:8080";
    private Cache  cache   = new Cache();
    private Kafka  kafka   = new Kafka();

    @Data
    public static class Cache {
        /** Seconds before a cached short-code entry expires in Redis. */
        private long ttlSeconds = 3600;
    }

    @Data
    public static class Kafka {
        private Topic topic = new Topic();

        @Data
        public static class Topic {
            private String clickEvents = "url.click.events";
        }
    }
}
