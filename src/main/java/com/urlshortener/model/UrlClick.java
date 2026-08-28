package com.urlshortener.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

// Written exclusively by the Kafka consumer — never in the synchronous redirect path
@Entity
@Table(name = "url_clicks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlClick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "url_id", nullable = false)
    private Url url;

    @Column(name = "clicked_at", nullable = false)
    private OffsetDateTime clickedAt;

    /** Null when the HTTP Referer header was absent. */
    @Column(name = "referrer", columnDefinition = "TEXT")
    private String referrer;
}
