package com.urlshortener.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.urlshortener.cache.UrlCacheService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Generates on-the-fly QR code PNGs for short URLs.
 * Generated images are never persisted; they are cached in Redis keyed by
 * qrcode:{shortCode}:{size} and evicted through the same UrlCacheService.evict() path
 * that already handles the redirect cache — no second invalidation path.
 */
@Service
public class QrCodeService {

    // Size is clamped, not rejected: an out-of-range pixel count is a display preference,
    // not a semantic error, so silent normalisation beats a 400.
    static final int MIN_SIZE     = 100;
    static final int MAX_SIZE     = 1000;
    static final int DEFAULT_SIZE = 300;

    private final UrlCacheService urlCacheService;

    public QrCodeService(UrlCacheService urlCacheService) {
        this.urlCacheService = urlCacheService;
    }

    public byte[] getOrGenerate(String shortUrl, String shortCode, int requestedSize) {
        int size = clamp(requestedSize);
        return urlCacheService.getQrCode(shortCode, size)
                .orElseGet(() -> {
                    byte[] png = generatePng(shortUrl, size);
                    urlCacheService.putQrCode(shortCode, size, png);
                    return png;
                });
    }

    /** Package-visible so unit tests can assert boundary behaviour directly. */
    static int clamp(int size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    private byte[] generatePng(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix   = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new RuntimeException("QR code generation failed for: " + content, e);
        }
    }
}
