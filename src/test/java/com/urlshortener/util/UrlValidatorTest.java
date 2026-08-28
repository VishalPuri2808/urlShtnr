package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * All SSRF tests use IP literals so no outbound DNS is needed — keeps CI fast and
 * hermetic.  203.0.113.0/24 is TEST-NET-3 (RFC 5737): globally routable, not private.
 */
class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    // ── Valid URLs ──────────────────────────────────────────────────────────

    @Test
    void validate_publicHttps_passes() {
        assertThatCode(() -> validator.validate("https://203.0.113.1/path?q=1"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_publicHttp_passes() {
        assertThatCode(() -> validator.validate("http://203.0.113.1/page"))
                .doesNotThrowAnyException();
    }

    // ── Malformed / forbidden schemes ───────────────────────────────────────

    @Test
    void validate_bareString_throws() {
        assertThatThrownBy(() -> validator.validate("not-a-url"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_ftpScheme_throws() {
        assertThatThrownBy(() -> validator.validate("ftp://203.0.113.1/file"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("ftp");
    }

    @Test
    void validate_javascriptScheme_throws() {
        assertThatThrownBy(() -> validator.validate("javascript:alert(1)"))
                .isInstanceOf(InvalidUrlException.class);
    }

    // ── SSRF guardrail — private / reserved ranges ──────────────────────────

    @Test
    void validate_localhost_throws() {
        // localhost typically resolves to 127.0.0.1 (loopback)
        assertThatThrownBy(() -> validator.validate("http://localhost/secret"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_loopbackIpv4_throws() {
        assertThatThrownBy(() -> validator.validate("http://127.0.0.1/admin"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_rfc1918_10_throws() {
        assertThatThrownBy(() -> validator.validate("http://10.0.0.1/internal"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_rfc1918_172_throws() {
        assertThatThrownBy(() -> validator.validate("http://172.16.0.1/internal"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_rfc1918_192168_throws() {
        assertThatThrownBy(() -> validator.validate("http://192.168.1.100/router"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_linkLocalAwsMetadata_throws() {
        // 169.254.169.254 is the AWS/GCP/Azure instance-metadata endpoint
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_loopbackIpv6_throws() {
        assertThatThrownBy(() -> validator.validate("http://[::1]/internal"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void validate_anyLocalAddress_throws() {
        assertThatThrownBy(() -> validator.validate("http://0.0.0.0/"))
                .isInstanceOf(InvalidUrlException.class);
    }
}
