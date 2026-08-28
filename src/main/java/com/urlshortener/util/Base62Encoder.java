package com.urlshortener.util;

import org.springframework.stereotype.Component;

/**
 * Converts a positive DB-generated ID to a base62 string short code.
 *
 * DESIGN CHOICE — sequential IDs, not random:
 *   Codes are strictly sequential (1→"1", 62→"10", …).  A motivated actor can
 *   enumerate all valid short codes by incrementing a counter.  This is a
 *   KNOWN LIMITATION documented in ARCHITECTURE.md (risk section).  The
 *   trade-off is zero collision probability and no retry logic; if enumeration
 *   prevention is later required, a Hashids/shuffle layer can be added on top
 *   without changing the storage model.
 */
@Component
public class Base62Encoder {

    // Characters ordered 0-9, a-z, A-Z — 62 symbols
    static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length(); // 62

    public String encode(long id) {
        if (id <= 0) throw new IllegalArgumentException("ID must be positive, got: " + id);
        StringBuilder sb = new StringBuilder();
        long n = id;
        while (n > 0) {
            sb.append(ALPHABET.charAt((int) (n % BASE)));
            n /= BASE;
        }
        return sb.reverse().toString();
    }

    public long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            result = result * BASE + ALPHABET.indexOf(c);
        }
        return result;
    }
}
