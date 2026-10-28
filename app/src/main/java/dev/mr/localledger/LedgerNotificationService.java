package dev.mr.localledger;

import android.app.Notification;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reads only newly posted notifications from packages the owner explicitly selected. */
public final class LedgerNotificationService extends NotificationListenerService {
    private static final int MAX_TEXT_LENGTH = 8192;
    private static final int MAX_TITLE_LENGTH = 256;
    private final Map<String, Long> recentEvents = new LinkedHashMap<>();
    private AppSettings settings;
    private boolean destroyed;
    private boolean connected;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification posted) {
        if (posted == null || destroyed || !connected || !settings.isCaptureEnabled()) return;
        // The first version has no work-profile support; do not mix another profile's notifications.
        if (!Process.myUserHandle().equals(posted.getUser())) return;
        String packageName = posted.getPackageName();
        if (!settings.isPackageEnabled(packageName)) return;
        long diagnosticNumber = settings.beginCaptureDiagnostic(PaymentEvent.NOTIFICATION, packageName);
        boolean bank = settings.isBankPackage(packageName);
        // Unknown non-bank sources have no recognition rules yet; do not read unused bodies.
        if (!bank && !settings.isAccessibilityPackageEnabled(packageName)) {
            diagnose(diagnosticNumber, "filtered", "no_rule");
            return;
        }
        Notification notification = posted.getNotification();
        if (notification == null) {
            diagnose(diagnosticNumber, "filtered", "metadata_unavailable");
            return;
        }
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            diagnose(diagnosticNumber, "filtered", "group_summary");
            return;
        }
        String text;
        try {
            Bundle extras = notification.extras;
            CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title == null && extras != null) title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
            if (title != null && title.length() > MAX_TITLE_LENGTH) {
                diagnose(diagnosticNumber, "filtered", "title_too_long");
                return;
            }
            // Reject unsupported wallet/chat headers before converting or retaining their message bodies.
            boolean wechat = "com.tencent.mm".equals(packageName);
            boolean wallet = wechat
                    || "com.eg.android.AlipayGphone".equals(packageName) || "com.unionpay".equals(packageName);
            boolean titleSupported = wallet && RecognitionEngine.acceptsNotificationTitle(
                    packageName, title == null ? "" : title.toString());
            // Inspect fixed structural metadata only; do not read message arrays or action/reply text.
            NotificationMetadata metadata = inspectMetadata(notification, extras);
            settings.recordNotificationMetadata(packageName, diagnosticNumber, titleSupported,
                    metadata.messageCategory, metadata.templateKind, metadata.hasMessages,
                    metadata.hasHistoric, metadata.hasRemoteInput);
            if (wallet && !titleSupported) {
                diagnose(diagnosticNumber, "filtered", "title_unsupported");
                return;
            }
            // Some service notices use CATEGORY_MESSAGE without conversation payloads. Allow that
            // category only for a supported WeChat payment header; the parser keeps it review-only.
            // A matching header is a template hint, not authentication of a payment account.
            if (metadata.hasConversationPayload()
                    || (metadata.messageCategory && !(wechat && titleSupported))) {
                diagnose(diagnosticNumber, "filtered", "message_notification");
                return;
            }
            text = extractText(extras);
        } catch (RuntimeException malformedNotification) {
            // A source app may post malformed extras. Do not log their contents or stop the listener.
            settings.recordCaptureIssue(AppSettings.CAPTURE_ISSUE_SOURCE_UNAVAILABLE);
            diagnose(diagnosticNumber, "failed", "metadata_unavailable");
            return;
        }
        if (text == null || text.isEmpty()) {
            diagnose(diagnosticNumber, "filtered", text == null ? "body_limit" : "empty_text");
            return;
        }

        long observedAt = posted.getPostTime() > 0 ? posted.getPostTime() : System.currentTimeMillis();
        // The content digest matters: a bank or wallet can update an existing notification in place.
        String eventKey = posted.getKey() + ":" + observedAt + ":" + CaptureDispatcher.digest(text);
        synchronized (recentEvents) {
            if (recentEvents.containsKey(eventKey)) {
                diagnose(diagnosticNumber, "duplicate", "same_event");
                return;
            }
        }

        String kind = bank ? PaymentEvent.BANK_NOTIFICATION : PaymentEvent.NOTIFICATION;
        CaptureDispatcher.submit(this, new PaymentEvent(packageName,
                AppSettings.getPackageLabel(packageName), kind, eventKey, text, observedAt), diagnosticNumber, processed -> {
            // Do not cache rejected/failed writes: a later callback can retry the notification.
            if (!processed || destroyed) return;
            synchronized (recentEvents) {
                recentEvents.put(eventKey, observedAt);
                while (recentEvents.size() > 256) {
                    Iterator<String> iterator = recentEvents.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
            }
        });
    }

    private void diagnose(long diagnosticNumber, String stage, String reason) {
        settings.updateCaptureDiagnostic(PaymentEvent.NOTIFICATION, diagnosticNumber, stage, reason);
    }

    private static String extractText(Bundle extras) {
        if (extras == null) return "";
        NotificationText parts = new NotificationText();
        if (!parts.add(extras.getCharSequence(Notification.EXTRA_TITLE))
                || !parts.add(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
                || !parts.add(extras.getCharSequence(Notification.EXTRA_TEXT))
                || !parts.add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))) return null;
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            if (lines.length > 80) return null;
            for (CharSequence line : lines) if (!parts.add(line)) return null;
        }
        return String.join("\n", parts.values);
    }

    private static NotificationMetadata inspectMetadata(Notification notification, Bundle extras) {
        NotificationMetadata metadata = new NotificationMetadata();
        metadata.messageCategory = Notification.CATEGORY_MESSAGE.equals(notification.category);
        if (extras != null) {
            String template = extras.getString("android.template", "");
            metadata.templateKind = template == null || template.isEmpty() ? "none"
                    : template.contains("MessagingStyle") ? "messages"
                    : template.contains("BigTextStyle") ? "big_text"
                    : template.contains("InboxStyle") ? "inbox" : "other";
            metadata.hasMessages = extras.containsKey(Notification.EXTRA_MESSAGES);
            metadata.hasHistoric = extras.containsKey("android.messages.historic");
        }
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                    metadata.hasRemoteInput = true;
                    break;
                }
            }
        }
        return metadata;
    }

    private static final class NotificationMetadata {
        boolean messageCategory;
        String templateKind = "none";
        boolean hasMessages;
        boolean hasHistoric;
        boolean hasRemoteInput;

        boolean hasConversationPayload() {
            return "messages".equals(templateKind) || hasMessages || hasHistoric || hasRemoteInput;
        }
    }

    private static final class NotificationText {
        final Set<String> values = new LinkedHashSet<>();
        int length;

        boolean add(CharSequence value) {
            if (value == null) return true;
            if (value.length() > MAX_TEXT_LENGTH) return false;
            String text = value.toString().trim();
            if (text.isEmpty() || values.contains(text)) return true;
            int extra = text.length() + (values.isEmpty() ? 0 : 1);
            if (extra > MAX_TEXT_LENGTH - length) return false;
            values.add(text);
            length += extra;
            return true;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        diagnose(0, settings.isCaptureEnabled() ? "ready" : "paused",
                settings.isCaptureEnabled() ? "service_ready" : "capture_disabled");
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        CaptureDispatcher.cancelPendingNotifications();
        diagnose(0, "disconnected", "service_disconnected");
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        connected = false;
        CaptureDispatcher.cancelPendingNotifications();
        diagnose(0, "disconnected", "service_disconnected");
        synchronized (recentEvents) {
            recentEvents.clear();
        }
        super.onDestroy();
    }
}
