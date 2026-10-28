package dev.mr.localledger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared bounded worker. Raw captures exist only in this process and are never spooled to disk. */
final class CaptureDispatcher {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48),
            runnable -> new Thread(runnable, "ledger-capture"),
            new ThreadPoolExecutor.AbortPolicy());
    private static LedgerDb database;

    static {
        WORKER.allowCoreThreadTimeOut(true);
    }

    interface Completion {
        /** Called on the main thread. False includes revoked consent and a failed write. */
        void onComplete(boolean processed);
    }

    private CaptureDispatcher() {}

    static boolean submit(Context context, PaymentEvent event) {
        return submit(context, event, null);
    }

    static boolean submit(Context context, PaymentEvent event, Completion completion) {
        return submit(context, event, 0, completion);
    }

    static boolean submit(Context context, PaymentEvent event, long diagnosticNumber, Completion completion) {
        if (event == null || event.eventKey.isEmpty()) return false;
        Context appContext = context.getApplicationContext();
        AppSettings settings = new AppSettings(appContext);
        if (!isAuthorized(settings, event)) {
            settings.updateCaptureDiagnostic(event.sourceKind, diagnosticNumber, "ignored", "consent_changed");
            return false;
        }
        String token = settings.authorizationToken(event.sourcePackage, event.sourceKind);
        String identity = digest(event.sourcePackage + "\n" + event.sourceKind + "\n" + event.eventKey + "\n" + token);
        if (!IN_FLIGHT.add(identity)) {
            settings.updateCaptureDiagnostic(event.sourceKind, diagnosticNumber, "queued", "in_flight");
            return false;
        }
        CaptureWork work = new CaptureWork(appContext, event, token, identity, completion, diagnosticNumber);
        try {
            settings.updateCaptureDiagnostic(event.sourceKind, diagnosticNumber, "queued", "queue_accepted");
            WORKER.execute(work);
            return true;
        } catch (RejectedExecutionException queueFull) {
            IN_FLIGHT.remove(identity);
            // Keep earlier accepted events. Expose the loss instead of silently dropping an old payment.
            settings.recordCaptureIssue(AppSettings.CAPTURE_ISSUE_QUEUE_FULL);
            settings.updateCaptureDiagnostic(event.sourceKind, diagnosticNumber, "failed", "queue_full");
            return false;
        }
    }

    /** Purge only work whose source consent changed, immediately releasing queued raw text. */
    static void pruneUnauthorized(Context context) {
        AppSettings settings = new AppSettings(context);
        for (Runnable runnable : WORKER.getQueue().toArray(new Runnable[0])) {
            if (!(runnable instanceof CaptureWork)) continue;
            CaptureWork work = (CaptureWork) runnable;
            if (!work.isAuthorized(settings) && WORKER.getQueue().remove(work)) {
                settings.updateCaptureDiagnostic(work.event.sourceKind, work.diagnosticNumber, "ignored", "consent_changed");
                work.finish(false);
            }
        }
    }

    static void cancelPendingAccessibility() {
        cancelPending(true);
    }

    static void cancelPendingNotifications() {
        cancelPending(false);
    }

    private static void cancelPending(boolean accessibility) {
        for (Runnable runnable : WORKER.getQueue().toArray(new Runnable[0])) {
            if (!(runnable instanceof CaptureWork)) continue;
            CaptureWork work = (CaptureWork) runnable;
            if (accessibility == PaymentEvent.ACCESSIBILITY.equals(work.event.sourceKind)
                    && WORKER.getQueue().remove(work)) {
                new AppSettings(work.context).updateCaptureDiagnostic(work.event.sourceKind,
                        work.diagnosticNumber, "ignored", "service_disconnected");
                work.finish(false);
            }
        }
    }

    private static boolean isAuthorized(AppSettings settings, PaymentEvent event) {
        if (!settings.isCaptureEnabled() || !settings.isPackageEnabled(event.sourcePackage)) return false;
        if (PaymentEvent.ACCESSIBILITY.equals(event.sourceKind)) {
            return settings.isAccessibilityCaptureEnabled()
                    && settings.isAccessibilityPackageEnabled(event.sourcePackage);
        }
        if (PaymentEvent.BANK_NOTIFICATION.equals(event.sourceKind)) {
            return settings.isBankPackage(event.sourcePackage);
        }
        return PaymentEvent.NOTIFICATION.equals(event.sourceKind);
    }

    private static final class CaptureWork implements Runnable {
        private final Context context;
        private final PaymentEvent event;
        private final String consentToken;
        private final String identity;
        private final Completion completion;
        private final long diagnosticNumber;

        CaptureWork(Context context, PaymentEvent event, String consentToken,
                    String identity, Completion completion, long diagnosticNumber) {
            this.context = context;
            this.event = event;
            this.consentToken = consentToken;
            this.identity = identity;
            this.completion = completion;
            this.diagnosticNumber = diagnosticNumber;
        }

        boolean isAuthorized(AppSettings settings) {
            return CaptureDispatcher.isAuthorized(settings, event)
                    && consentToken.equals(settings.authorizationToken(event.sourcePackage, event.sourceKind));
        }

        @Override
        public void run() {
            boolean processed = false;
            AppSettings settings = new AppSettings(context);
            try {
                if (!isAuthorized(settings)) {
                    settings.updateCaptureDiagnostic(event.sourceKind, diagnosticNumber, "ignored", "consent_changed");
                    return;
                }
                settings.updateCaptureDiagnostic(event.sourceKind, diagnosticNumber, "processing", "processing");
                RecognitionEngine.Draft ruleTrace = new RecognitionEngine.Draft();
                RecognitionEngine.Draft diagnosticDraft = RecognitionEngine.parse(event, ruleTrace);
                String shape = RecognitionEngine.templateShape(event, ruleTrace);
                if (!isAuthorized(settings)) {
                    settings.updateCaptureDiagnostic(event.sourceKind, diagnosticNumber, "ignored", "consent_changed");
                    return;
                }
                settings.recordRuleDetails(event.sourcePackage, event.sourceKind, diagnosticNumber, consentToken,
                        shape, diagnosticDraft == null ? "" : diagnosticDraft.direction,
                        diagnosticDraft != null && diagnosticDraft.merchant != null
                                && !diagnosticDraft.merchant.isEmpty() && !"待补充商户".equals(diagnosticDraft.merchant),
                        diagnosticDraft != null && diagnosticDraft.transactionId != null
                                && !diagnosticDraft.transactionId.isEmpty());
                if (database == null) database = new LedgerDb(context);
                String outcome = database.ingest(event);
                if ("已自动记账".equals(outcome)) {
                    settings.recordCaptureResult(event.sourceKind, diagnosticNumber, "recorded", "saved_recorded");
                } else if ("已补充待确认记录的商户".equals(outcome)) {
                    settings.recordCaptureResult(event.sourceKind, diagnosticNumber, "pending", "merchant_supplemented");
                } else if ("新增一笔待确认记录".equals(outcome) || "同一采集冲突已保留待确认".equals(outcome)) {
                    settings.recordCaptureResult(event.sourceKind, diagnosticNumber, "pending", "saved_pending");
                } else if ("未发现可记录的交易结果".equals(outcome)) {
                    settings.recordCaptureResult(event.sourceKind, diagnosticNumber, "ignored", "rule_not_matched");
                } else if ("相同采集事件已处理".equals(outcome) || "同一交易号与金额已记录或已删除".equals(outcome)) {
                    settings.recordCaptureResult(event.sourceKind, diagnosticNumber, "duplicate", "already_recorded");
                } else {
                    settings.recordCaptureResult(event.sourceKind, diagnosticNumber, "ignored", "processed");
                }
                processed = true;
            } catch (RuntimeException writeFailed) {
                // Persist a fixed diagnostic code, never exception text, source names or payment content.
                settings.recordCaptureIssue(AppSettings.CAPTURE_ISSUE_WRITE_FAILED);
                settings.recordCaptureResult(event.sourceKind, diagnosticNumber, "failed", "write_failed");
            } finally {
                finish(processed);
            }
        }

        void finish(boolean processed) {
            IN_FLIGHT.remove(identity);
            Completion callback = completion;
            // Capture the callback alone, so queued UI callbacks never retain a raw capture.
            if (callback != null) MAIN.post(() -> callback.onComplete(processed));
        }
    }

    static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                output.append(Character.forDigit((value >>> 4) & 15, 16));
                output.append(Character.forDigit(value & 15, 16));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Android", impossible);
        }
    }
}
