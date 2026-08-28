package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.*;

/**
 * Validates that a long URL is syntactically well-formed and does not target a
 * private or reserved IP range.
 *
 * SSRF CONTEXT:
 *   This service issues 302 redirects.  If an attacker stores a URL pointing at
 *   http://169.254.169.254/latest/meta-data/ (AWS instance-metadata), any
 *   server-side HTTP client that follows redirects would leak cloud credentials.
 *   We block known private/reserved ranges at creation time as a first line of
 *   defence.
 *
 * KNOWN LIMITATIONS:
 *   1. DNS rebinding: we resolve the hostname once at creation time.  If the DNS
 *      TTL expires and the record is changed to a private IP afterwards, the check
 *      is bypassed.  Mitigating this properly requires re-validation at redirect
 *      time, which is outside the scope of this service (we emit a 302, we don't
 *      fetch the target).
 *   2. Unresolvable hosts: we allow them (future-valid staging URLs, CDN names,
 *      etc.).  An operator may tighten this by returning true from the catch block.
 */
@Component
public class UrlValidator {

    public void validate(String rawUrl) {
        URI uri = parseUri(rawUrl);
        validateScheme(uri);
        validateHost(uri);
    }

    private URI parseUri(String rawUrl) {
        try {
            return new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("Malformed URL: " + e.getMessage());
        }
    }

    private void validateScheme(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new InvalidUrlException(
                    "Only http and https URLs are accepted"
                    + (scheme != null ? ", got scheme: " + scheme : ""));
        }
    }

    private void validateHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("URL must contain a valid host");
        }

        // Some JVM versions return IPv6 addresses wrapped in brackets — strip them
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return; // Unresolvable host — allow; see KNOWN LIMITATIONS above
        }

        if (isPrivateOrReserved(address)) {
            throw new InvalidUrlException(
                    "URLs resolving to private or reserved IP ranges are not permitted (SSRF guardrail)");
        }
    }

    private boolean isPrivateOrReserved(InetAddress addr) {
        return addr.isLoopbackAddress()     // 127.0.0.0/8, ::1
            || addr.isSiteLocalAddress()    // 10/8, 172.16/12, 192.168/16
            || addr.isLinkLocalAddress()    // 169.254.0.0/16, fe80::/10
            || addr.isAnyLocalAddress()     // 0.0.0.0 / ::
            || addr.isMulticastAddress()    // 224.0.0.0/4
            || isIpv4MappedPrivate(addr);   // ::ffff:127.0.0.1 etc.
    }

    /** Unwraps IPv4-mapped IPv6 addresses and re-checks the embedded IPv4. */
    private boolean isIpv4MappedPrivate(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) return false;
        byte[] b = addr.getAddress();
        // Mapped format: 10 zero bytes, then 0xFF 0xFF, then 4 IPv4 bytes
        boolean isMapped = b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0
                && b[8] == 0 && b[9] == 0
                && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
        if (!isMapped) return false;
        try {
            InetAddress ipv4 = InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
            return isPrivateOrReserved(ipv4);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
