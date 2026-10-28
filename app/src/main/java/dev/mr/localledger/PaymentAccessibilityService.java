package dev.mr.localledger;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Optional, local-only reading of payment screens. Never clicks, types, screenshots or sends data. */
public final class PaymentAccessibilityService extends AccessibilityService {
    private static final int MAX_NODES = 200;
    private static final int MAX_CHARACTERS = 8192;
    private static final int MAX_HEADER_CHARACTERS = 4096;
    private static final int MAX_HEADER_LINES = 24;
    private static final long CAPTURE_DELAY_MS = 260;
    private static final long MIN_CAPTURE_INTERVAL_MS = 850;
    private static volatile WeakReference<PaymentAccessibilityService> connectedService = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppSettings settings;
    private String pendingPackage;
    private long pendingDiagnosticNumber;
    private int pendingWindowId = -1;
    private String windowIdentity = "";
    private String pageSession = UUID.randomUUID().toString();
    private String lastDigest = "";
    private String lastAcceptedKey = "";
    private String lastAttemptDigest = "";
    private String lastAttemptKey = "";
    private String currentWindowClass = "";
    private String currentWindowPackage = "";
    private long lastEventAt;
    private long lastCaptureAt;
    private long lastSourceIssueAt = -60_000;
    private long captureSequence;
    private boolean captureScheduled;
    private boolean connected;
    private boolean destroyed;
    private boolean readModeAvailable = true;
    private final Runnable captureTask = () -> {
        captureScheduled = false;
        captureCurrentWindow();
    };
    private final Runnable filterTask = this::applyPackageFilter;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (preferences, key) -> {
        if (key == null || AppSettings.KEY_CAPTURE_ENABLED.equals(key)
                || AppSettings.KEY_ACCESSIBILITY_ENABLED.equals(key) || AppSettings.KEY_ENABLED_PACKAGES.equals(key)) {
            handler.removeCallbacks(filterTask);
            handler.post(filterTask);
        }
    };

    @Override
    protected void onServiceConnected() {
        if (settings != null) settings.unregisterListener(preferenceListener);
        destroyed = false;
        connected = true;
        connectedService = new WeakReference<>(this);
        settings = new AppSettings(this);
        settings.registerListener(preferenceListener);
        applyPackageFilter();
    }

    /** On-demand structural baseline for our own foreground window; reads no text or descriptions. */
    public static String inspectOwnWindow() {
        PaymentAccessibilityService service = connectedService.get();
        if (service == null || !service.connected || service.destroyed) {
            return "页面服务：未连接\n请先在系统无障碍设置中启用本应用的页面服务。";
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        AccessibilityNodeInfo root = null;
        boolean rootVisible = false;
        int rootChildren = -1;
        int nodeCount = 0;
        int visibleCount = 0;
        String result = "已检查完整结构";
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return "页面服务：已连接\n系统未返回当前窗口根节点，请回到本应用后重试。";
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !"dev.mr.localledger".contentEquals(rootPackage)) {
                return "页面服务：已连接\n请先返回本应用，再执行自身页面检查。";
            }
            rootVisible = root.isVisibleToUser();
            rootChildren = root.getChildCount();
            queue.add(root);
            root = null; // The queue now owns the root and recycles it like every other node.
            inspection:
            while (!queue.isEmpty() && nodeCount < MAX_NODES) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    CharSequence nodePackage = node.getPackageName();
                    if (nodePackage == null || !"dev.mr.localledger".contentEquals(nodePackage)) {
                        result = "遇到非本应用节点，已停止检查";
                        break;
                    }
                    nodeCount++;
                    if (node.isVisibleToUser()) visibleCount++;
                    int childCount = node.getChildCount();
                    if (childCount > MAX_NODES - nodeCount - queue.size()) {
                        result = "结构超过200节点上限，已停止检查";
                        break;
                    }
                    for (int i = 0; i < childCount; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child == null) {
                            result = "窗口结构发生变化，部分子节点不可用";
                            break inspection;
                        }
                        queue.addLast(child);
                    }
                } finally {
                    node.recycle();
                }
            }
        } catch (RuntimeException unavailableWindow) {
            result = "系统暂时未提供完整窗口结构，请重试";
        } finally {
            if (root != null) root.recycle();
            while (!queue.isEmpty()) queue.removeFirst().recycle();
        }
        return "页面服务：已连接\n检查对象：本应用\n根节点可见：" + (rootVisible ? "是" : "否")
                + "\n根节点子节点数：" + (rootChildren < 0 ? "未取得" : rootChildren)
                + "\n已检查节点数：" + nodeCount + "\n可见节点数：" + visibleCount
                + "\n结果：" + result + "\n仅检查结构，不读取或保存页面文字。";
    }

    private void clearConnectedService() {
        WeakReference<PaymentAccessibilityService> reference = connectedService;
        if (reference.get() == this) reference.clear();
    }

    private boolean isEnabled() {
        return !destroyed && connected && settings != null
                && settings.isCaptureEnabled() && settings.isAccessibilityCaptureEnabled();
    }

    private void applyPackageFilter() {
        if (settings == null || !connected || destroyed) return;
        handler.removeCallbacks(captureTask);
        captureScheduled = false;
        pendingPackage = null;
        pendingWindowId = -1;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) throw new IllegalStateException();
            Set<String> selected = settings.getAccessibilityPackages();
            // A null/empty packageNames means every app to Android, so use an impossible app when disabled.
            info.packageNames = isEnabled() && !selected.isEmpty()
                    ? selected.toArray(new String[0])
                    : new String[]{"dev.mr.localledger.capture.disabled"};
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 200;
            info.flags &= ~(AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS);
            setServiceInfo(info);
            readModeAvailable = true;
            if (!settings.isCaptureEnabled()) diagnose(0, "paused", "capture_disabled");
            else if (!settings.isAccessibilityCaptureEnabled()) diagnose(0, "paused", "page_capture_disabled");
            else if (selected.isEmpty()) diagnose(0, "ready", "no_selected_sources");
            else diagnose(0, "ready", "service_ready");
        } catch (RuntimeException unavailableService) {
            readModeAvailable = false;
            reportSourceIssue();
            diagnose(0, "failed", "metadata_unavailable");
        }
        resetSession();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isEnabled() || event == null || event.getPackageName() == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        String packageName = event.getPackageName().toString();
        if (!settings.isAccessibilityPackageEnabled(packageName)) return;
        long diagnosticNumber = settings.beginCaptureDiagnostic(PaymentEvent.ACCESSIBILITY, packageName);
        if (!packageName.equals(currentWindowPackage)) currentWindowClass = "";
        currentWindowPackage = packageName;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentWindowClass = event.getClassName() == null ? "" : event.getClassName().toString();
        }
        if (isChatWindow(currentWindowClass)) {
            cancelScheduledCapture();
            resetSession();
            diagnose(diagnosticNumber, "filtered", "chat_window");
            return;
        }

        long now = SystemClock.elapsedRealtime();
        String identity = packageName + ":" + event.getWindowId();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) identity += ":" + event.getClassName();
        if (lastEventAt == 0 || now - lastEventAt > 30_000
                || (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !identity.equals(windowIdentity))) {
            resetSession();
            windowIdentity = identity;
        }
        lastEventAt = now;
        pendingPackage = packageName;
        pendingDiagnosticNumber = diagnosticNumber;
        pendingWindowId = event.getWindowId();
        diagnose(diagnosticNumber, "scheduled", "capture_scheduled");
        // Keep the first scheduled capture, so animated/content-heavy apps cannot postpone it indefinitely.
        if (!captureScheduled) {
            long throttle = Math.max(0, MIN_CAPTURE_INTERVAL_MS - (now - lastCaptureAt));
            captureScheduled = true;
            handler.postDelayed(captureTask, Math.max(CAPTURE_DELAY_MS, throttle));
        }
    }

    private void captureCurrentWindow() {
        String packageName = pendingPackage;
        long diagnosticNumber = pendingDiagnosticNumber;
        if (!isEnabled() || packageName == null || !settings.isAccessibilityPackageEnabled(packageName)
                || isChatWindow(currentWindowClass)) return;
        if (!readModeAvailable) {
            diagnose(diagnosticNumber, "failed", "metadata_unavailable");
            return;
        }
        lastCaptureAt = SystemClock.elapsedRealtime();
        boolean requested = "com.tencent.mm".equals(packageName);
        boolean active = false;
        int rootChildren = -1;
        boolean rootImportant = false;
        boolean eventWindowMatchesRoot = false;
        int eventWindowId = pendingWindowId;
        AccessibilityServiceInfo temporaryInfo = null;
        int originalFlags = 0;
        boolean restoreMode = false;
        AccessibilityNodeInfo root = null;
        ScreenCapture capture = null;
        String readFailure = "";
        try {
            // This ordinary visibility flag is scoped to one authorized WeChat read. It does
            // not change package filters or relax sensitive, editor or chat-page rejection.
            if (requested) {
                temporaryInfo = getServiceInfo();
                if (temporaryInfo == null) throw new IllegalStateException();
                originalFlags = temporaryInfo.flags;
                restoreMode = true;
                temporaryInfo.flags = originalFlags | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                setServiceInfo(temporaryInfo);
                AccessibilityServiceInfo appliedInfo = getServiceInfo();
                active = appliedInfo != null && (appliedInfo.flags
                        & AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
                if (!active) throw new IllegalStateException();
            }
            root = getRootInActiveWindow();
            if (root == null) {
                readFailure = "window_unavailable";
            } else if (root.getPackageName() == null || !packageName.contentEquals(root.getPackageName())) {
                readFailure = "window_changed";
            } else {
                // This diagnostic stores only counts and booleans; window IDs are never persisted.
                rootChildren = root.getChildCount();
                rootImportant = root.isImportantForAccessibility();
                eventWindowMatchesRoot = eventWindowId >= 0 && root.getWindowId() == eventWindowId;
                AccessibilityNodeInfo captureRoot = root;
                root = null; // The bounded collector owns and recycles every node from here.
                capture = collectVisibleText(captureRoot, packageName);
            }
        } catch (RuntimeException unavailableWindow) {
            // The OS can withdraw access while the owner changes permissions or switches apps.
            reportSourceIssue();
            readFailure = "metadata_unavailable";
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (RuntimeException unavailableRoot) {
                    reportSourceIssue();
                    readFailure = "metadata_unavailable";
                }
            }
            if (restoreMode) {
                try {
                    temporaryInfo.flags = originalFlags;
                    setServiceInfo(temporaryInfo);
                    AccessibilityServiceInfo restoredInfo = getServiceInfo();
                    if (restoredInfo == null || restoredInfo.flags != originalFlags) {
                        throw new IllegalStateException();
                    }
                } catch (RuntimeException unavailableRestore) {
                    // Fail closed until reconnect or a successful package-filter refresh.
                    readModeAvailable = false;
                    cancelScheduledCapture();
                    reportSourceIssue();
                    readFailure = "metadata_unavailable";
                }
            }
            settings.recordAccessibilityReadMode(packageName, diagnosticNumber, requested, active,
                    rootChildren, rootImportant, eventWindowMatchesRoot);
        }
        if (!readFailure.isEmpty()) {
            diagnose(diagnosticNumber, "metadata_unavailable".equals(readFailure) ? "failed" : "filtered", readFailure);
            return;
        }
        if (capture == null) {
            diagnose(diagnosticNumber, "failed", "metadata_unavailable");
            return;
        }
        CaptureStructure structure = capture.structure;
        settings.recordAccessibilityStructure(packageName, diagnosticNumber, structure.nodeCount,
                structure.visibleNodeCount, structure.textNodeCount, structure.webViewCount,
                structure.sensitiveNodeCount, structure.rootVisible);
        if (!capture.reason.isEmpty()) {
            diagnose(diagnosticNumber, "filtered", capture.reason);
            return;
        }
        String text = capture.text;
        String digest = CaptureDispatcher.digest(text);
        // Layout can settle after text. Re-read a changed header under the same raw event key,
        // so exact pending duplicates can gain a merchant without creating a new event identity.
        String captureDigest = CaptureDispatcher.digest(digest + "\n" + capture.headerText);
        if (captureDigest.equals(lastDigest)) {
            diagnose(diagnosticNumber, "duplicate", "same_screen");
            return;
        }
        if (!digest.equals(lastAttemptDigest)) {
            captureSequence++;
            lastAttemptDigest = digest;
            lastAttemptKey = pageSession + ":" + captureSequence + ":" + digest;
        }
        String eventKey = lastAttemptKey;
        boolean accepted = CaptureDispatcher.submit(this, new PaymentEvent(packageName,
                AppSettings.getPackageLabel(packageName), PaymentEvent.ACCESSIBILITY,
                eventKey, text, capture.headerText, System.currentTimeMillis()), diagnosticNumber, processed -> {
            if (!processed && eventKey.equals(lastAcceptedKey)) lastDigest = "";
        });
        if (accepted) {
            lastDigest = captureDigest;
            lastAcceptedKey = eventKey;
        }
    }

    private void diagnose(long diagnosticNumber, String stage, String reason) {
        if (settings != null) settings.updateCaptureDiagnostic(PaymentEvent.ACCESSIBILITY, diagnosticNumber, stage, reason);
    }

    private static ScreenCapture collectVisibleText(AccessibilityNodeInfo root, String packageName) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        List<AccessibilityNodeInfo> readable = new ArrayList<>();
        ScreenText text = new ScreenText();
        HeaderText header = "com.eg.android.AlipayGphone".equals(packageName) ? new HeaderText() : null;
        CaptureStructure structure = new CaptureStructure();
        queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited < MAX_NODES) {
                AccessibilityNodeInfo node = queue.removeFirst();
                boolean retained = false;
                try {
                    visited++;
                    structure.nodeCount++;
                    CharSequence nodePackage = node.getPackageName();
                    if (nodePackage == null || !packageName.contentEquals(nodePackage)) {
                        return ScreenCapture.rejected("package_mismatch", structure);
                    }
                    boolean visible = node.isVisibleToUser();
                    if (visited == 1) {
                        structure.rootVisible = visible;
                        if (header != null) node.getBoundsInScreen(header.windowBounds);
                    }
                    if (visible) structure.visibleNodeCount++;
                    CharSequence className = node.getClassName();
                    if (className != null && className.toString().contains("WebView")) structure.webViewCount++;
                    if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive()) {
                        structure.sensitiveNodeCount++;
                        return ScreenCapture.rejected("sensitive_page", structure);
                    }
                    boolean input = node.isPassword() || node.isEditable()
                            || (className != null && className.toString().contains("EditText"));
                    // Reject the whole screen before reading an editor or any of its children.
                    // This deliberately also skips ordinary chat/search/payment-entry screens.
                    if (input) return ScreenCapture.rejected("input_present", structure);
                    if (className != null && isChatWindow(className.toString())) return ScreenCapture.rejected("chat_window", structure);
                    int childCount = node.getChildCount();
                    if (childCount > MAX_NODES - visited - queue.size()) return ScreenCapture.rejected("node_limit", structure);
                    for (int i = 0; i < childCount; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child == null) return ScreenCapture.rejected("incomplete_tree", structure);
                        queue.addLast(child);
                    }
                    // An invisible container can have visible descendants. Inspect its child metadata,
                    // while keeping its own text and description out of the readable set.
                    if (visible) {
                        readable.add(node);
                        retained = true;
                    }
                } finally {
                    if (!retained) node.recycle();
                }
            }
            // Parsing an incomplete prefix can mistake a balance for the missing payment amount.
            if (!queue.isEmpty()) return ScreenCapture.rejected("node_limit", structure);
            // Read text only after the complete bounded tree passes the editor/chat checks.
            for (AccessibilityNodeInfo node : readable) {
                CharSequence value = node.getText();
                CharSequence description = node.getContentDescription();
                if ((value != null && value.length() > 0) || (description != null && description.length() > 0)) {
                    structure.textNodeCount++;
                }
                if (!text.add(value) || !text.add(description)) return ScreenCapture.rejected("text_limit", structure);
                if (header != null && ((value != null && value.length() > 0)
                        || (description != null && description.length() > 0))) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    header.add(value, description, bounds);
                }
            }
        } finally {
            while (!queue.isEmpty()) queue.removeFirst().recycle();
            for (AccessibilityNodeInfo node : readable) node.recycle();
        }
        String body = String.join("\n", text.parts);
        return body.isEmpty() ? ScreenCapture.rejected(!structure.rootVisible && structure.visibleNodeCount == 0
                ? "root_not_visible" : "tree_no_visible_text", structure)
                : new ScreenCapture(body, header == null ? "" : header.build(), "", structure);
    }

    private static final class CaptureStructure {
        int nodeCount;
        int visibleNodeCount;
        int textNodeCount;
        int webViewCount;
        int sensitiveNodeCount;
        boolean rootVisible;
    }

    private static final class ScreenCapture {
        final String text;
        final String headerText;
        final String reason;
        final CaptureStructure structure;

        ScreenCapture(String text, String headerText, String reason, CaptureStructure structure) {
            this.text = text;
            this.headerText = headerText;
            this.reason = reason;
            this.structure = structure;
        }

        static ScreenCapture rejected(String reason, CaptureStructure structure) {
            return new ScreenCapture("", "", reason, structure);
        }
    }

    /** Geometry is auxiliary only: it never changes the full text or chooses a merchant. */
    private static final class HeaderText {
        final Rect windowBounds = new Rect();
        final List<HeaderPart> parts = new ArrayList<>();

        void add(CharSequence value, CharSequence description, Rect bounds) {
            long width = (long) windowBounds.right - windowBounds.left;
            long height = (long) windowBounds.bottom - windowBounds.top;
            if (width <= 0 || height <= 0 || width > 32768 || height > 32768 || bounds.isEmpty()) return;
            // Discard off-window text, the lower half, and broad container summaries. A missing
            // or unreliable rectangle disables this hint rather than guessing a page order.
            if (!windowBounds.contains(bounds) || bounds.bottom > (long) windowBounds.top + height / 2
                    || (long) bounds.bottom - bounds.top > height / 4) return;
            add(value, bounds);
            add(description, bounds);
        }

        private void add(CharSequence value, Rect bounds) {
            if (value == null || value.length() == 0) return;
            String text = value.toString().trim();
            if (!text.isEmpty()) parts.add(new HeaderPart(text, bounds.top, bounds.left));
        }

        String build() {
            // List.sort is stable, retaining the source order of lines sharing the same box.
            parts.sort((first, second) -> {
                int top = Integer.compare(first.top, second.top);
                return top != 0 ? top : Integer.compare(first.left, second.left);
            });
            Set<String> lines = new LinkedHashSet<>();
            int length = 0;
            for (HeaderPart part : parts) {
                for (String candidate : part.text.split("\\r?\\n")) {
                    String line = candidate.trim();
                    if (line.isEmpty() || lines.contains(line)) continue;
                    int extra = line.length() + (lines.isEmpty() ? 0 : 1);
                    if (extra > MAX_HEADER_CHARACTERS - length) return "";
                    lines.add(line);
                    length += extra;
                    if (lines.size() == MAX_HEADER_LINES) return String.join("\n", lines);
                }
            }
            return String.join("\n", lines);
        }
    }

    private static final class HeaderPart {
        final String text;
        final int top;
        final int left;

        HeaderPart(String text, int top, int left) {
            this.text = text;
            this.top = top;
            this.left = left;
        }
    }

    private static boolean isChatWindow(String className) {
        String name = className.toLowerCase(Locale.ROOT);
        return name.contains("chatting") || name.contains(".chat.")
                || name.contains(".chatapp.") || name.contains(".conversation.");
    }

    private static final class ScreenText {
        final Set<String> parts = new LinkedHashSet<>();
        int length;

        boolean add(CharSequence value) {
            if (value == null) return true;
            if (value.length() > MAX_CHARACTERS) return false;
            String text = value.toString().trim();
            if (text.isEmpty() || parts.contains(text)) return true;
            int extra = text.length() + (parts.isEmpty() ? 0 : 1);
            if (extra > MAX_CHARACTERS - length) return false;
            parts.add(text);
            length += extra;
            return true;
        }
    }

    private void resetSession() {
        pageSession = UUID.randomUUID().toString();
        lastDigest = "";
        lastAcceptedKey = "";
        lastAttemptDigest = "";
        lastAttemptKey = "";
        captureSequence = 0;
    }

    private void reportSourceIssue() {
        long now = SystemClock.elapsedRealtime();
        if (settings != null && now - lastSourceIssueAt >= 60_000) {
            lastSourceIssueAt = now;
            settings.recordCaptureIssue(AppSettings.CAPTURE_ISSUE_SOURCE_UNAVAILABLE);
        }
    }

    @Override
    public void onInterrupt() {
        cancelScheduledCapture();
        diagnose(0, "paused", "service_interrupted");
    }

    private void cancelScheduledCapture() {
        handler.removeCallbacks(captureTask);
        captureScheduled = false;
        pendingPackage = null;
        pendingDiagnosticNumber = 0;
        pendingWindowId = -1;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        clearConnectedService();
        cancelScheduledCapture();
        handler.removeCallbacks(filterTask);
        CaptureDispatcher.cancelPendingAccessibility();
        diagnose(0, "disconnected", "service_disconnected");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        connected = false;
        clearConnectedService();
        handler.removeCallbacksAndMessages(null);
        captureScheduled = false;
        pendingPackage = null;
        pendingWindowId = -1;
        CaptureDispatcher.cancelPendingAccessibility();
        diagnose(0, "disconnected", "service_disconnected");
        if (settings != null) settings.unregisterListener(preferenceListener);
        super.onDestroy();
    }
}
