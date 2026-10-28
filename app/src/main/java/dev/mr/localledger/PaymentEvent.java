package dev.mr.localledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A transient capture. The raw text must never be persisted or logged. */
public final class PaymentEvent {
    public static final String NOTIFICATION = "notification";
    public static final String ACCESSIBILITY = "accessibility";
    public static final String BANK_NOTIFICATION = "bank_notification";

    public final String sourcePackage;
    public final String sourceLabel;
    public final String sourceKind;
    public final String eventKey;
    public final String rawText;
    /** Optional, geometry-ordered page header. Transient, like rawText; never persist or log. */
    public final String headerText;
    public final long observedAt;
    public final boolean truncated;
    public final boolean invalidTimestamp;

    public PaymentEvent(String sourcePackage, String sourceLabel, String sourceKind,
                        String eventKey, String rawText, long observedAt) {
        this(sourcePackage, sourceLabel, sourceKind, eventKey, rawText, "", observedAt);
    }

    public PaymentEvent(String sourcePackage, String sourceLabel, String sourceKind,
                        String eventKey, String rawText, String headerText, long observedAt) {
        this.sourcePackage = bounded(sourcePackage, 240).trim();
        this.sourceLabel = bounded(sourceLabel, 120).trim();
        this.sourceKind = bounded(sourceKind, 40).trim();
        this.eventKey = eventKey == null ? "" : eventKey.length() > 2048
                ? "sha256:" + digest(eventKey) : eventKey;
        this.truncated = rawText != null && rawText.length() > 16000;
        this.rawText = bounded(rawText, 16000);
        // A cut-off header can change which merchant or amount appears adjacent to a status.
        this.headerText = headerText == null || headerText.length() > 4096 ? "" : headerText;
        long now = System.currentTimeMillis();
        this.invalidTimestamp = observedAt <= 0 || observedAt > now + 300_000L;
        this.observedAt = invalidTimestamp ? now : observedAt;
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : bytes) {
                int unsigned = item & 255;
                out.append(Character.forDigit(unsigned >>> 4, 16)).append(Character.forDigit(unsigned & 15, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
