package com.urlshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds all app.* YAML properties; nested static classes mirror YAML sub-trees
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String baseUrl = "http://localhost:8080";
    private Cache  cache   = new Cache();
    private Kafka  kafka   = new Kafka();

    public String getBaseUrl()          { return baseUrl; }
    public void   setBaseUrl(String v)  { this.baseUrl = v; }
    public Cache  getCache()            { return cache; }
    public void   setCache(Cache v)     { this.cache = v; }
    public Kafka  getKafka()            { return kafka; }
    public void   setKafka(Kafka v)     { this.kafka = v; }

    public static class Cache {
        /** Seconds before a cached short-code entry expires in Redis. */
        private long ttlSeconds = 3600;
        public long getTtlSeconds()        { return ttlSeconds; }
        public void setTtlSeconds(long v)  { this.ttlSeconds = v; }
    }

    public static class Kafka {
        private Topic topic = new Topic();
        public Topic getTopic()          { return topic; }
        public void  setTopic(Topic v)   { this.topic = v; }

        public static class Topic {
            private String clickEvents = "url.click.events";
            public String getClickEvents()         { return clickEvents; }
            public void   setClickEvents(String v) { this.clickEvents = v; }
        }
    }
}
