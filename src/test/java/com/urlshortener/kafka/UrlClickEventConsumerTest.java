package com.urlshortener.kafka;

import com.urlshortener.event.UrlClickedEvent;
import com.urlshortener.model.Url;
import com.urlshortener.model.UrlClick;
import com.urlshortener.repository.UrlClickRepository;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlClickEventConsumerTest {

    @Mock private UrlRepository      urlRepository;
    @Mock private UrlClickRepository urlClickRepository;
    @Captor private ArgumentCaptor<UrlClick> clickCaptor;

    private UrlClickEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UrlClickEventConsumer(urlRepository, urlClickRepository);
    }

    @Test
    void consume_knownUrlId_persistsClickRow() {
        Url url = Url.builder().id(1L).shortCode("abc")
                .longUrl("https://example.com").createdAt(OffsetDateTime.now()).active(true).build();
        when(urlRepository.findById(1L)).thenReturn(Optional.of(url));

        consumer.consume(new UrlClickedEvent(1L, "abc", "https://referrer.com", OffsetDateTime.now()));

        verify(urlClickRepository).save(clickCaptor.capture());
        assertThat(clickCaptor.getValue().getUrl()).isSameAs(url);
        assertThat(clickCaptor.getValue().getReferrer()).isEqualTo("https://referrer.com");
    }

    @Test
    void consume_unknownUrlId_skipsWithoutException() {
        when(urlRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatCode(() -> consumer.consume(
                new UrlClickedEvent(99L, "xyz", null, OffsetDateTime.now())))
                .doesNotThrowAnyException();
        verify(urlClickRepository, never()).save(any());
    }
}
