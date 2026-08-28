package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;

/** Request body for POST /api/v1/urls. */
@Data
public class CreateUrlRequest {

    @NotBlank(message = "longUrl must not be blank")
    private String longUrl;

    /** Optional vanity alias; auto-generates a base62 code when absent. */
    private String customAlias;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime expiresAt;
}
