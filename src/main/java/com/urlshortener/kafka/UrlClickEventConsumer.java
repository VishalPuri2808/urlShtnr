package com.urlshortener.kafka;

import com.urlshortener.event.UrlClickedEvent;
import com.urlshortener.model.UrlClick;
import com.urlshortener.repository.UrlClickRepository;
import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY writer to url_clicks — redirect handlers never touch this table directly.
 * Each message is processed in its own transaction; a save failure causes the
 * consumer to retry the offset on the next poll cycle.
 */
@Component
public class UrlClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UrlClickEventConsumer.class);

    private final UrlRepository      urlRepository;
    private final UrlClickRepository urlClickRepository;

    public UrlClickEventConsumer(UrlRepository urlRepository,
                                 UrlClickRepository urlClickRepository) {
        this.urlRepository      = urlRepository;
        this.urlClickRepository = urlClickRepository;
    }

    @KafkaListener(topics = "${app.kafka.topic.click-events}")
    @Transactional
    public void consume(UrlClickedEvent event) {
        urlRepository.findById(event.urlId()).ifPresentOrElse(
                url -> urlClickRepository.save(UrlClick.builder()
                        .url(url)
                        .clickedAt(event.clickedAt())
                        .referrer(event.referrer())
                        .build()),
                () -> log.warn("Received click event for unknown urlId: {}", event.urlId())
        );
    }
}
