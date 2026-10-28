package dev.mr.localledger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Local-only capture preferences. Neither capture route starts without the owner's consent. */
public final class AppSettings {
    public static final String KEY_CAPTURE_ENABLED = "capture_enabled";
    public static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_capture_enabled";
    public static final String KEY_ENABLED_PACKAGES = "enabled_packages";
    public static final String CAPTURE_ISSUE_QUEUE_FULL = "queue_full";
    public static final String CAPTURE_ISSUE_WRITE_FAILED = "write_failed";
    public static final String CAPTURE_ISSUE_SOURCE_UNAVAILABLE = "source_unavailable";

    private static final String[] STANDARD_PACKAGES = {
            "com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay",
            "com.taobao.taobao", "com.xunmeng.pinduoduo", "com.jingdong.app.mall",
            "com.ss.android.ugc.aweme", "com.icbc", "com.chinamworld.main",
            "com.android.bankabc", "com.chinamworld.bocmbci", "cmb.pb",
            "com.bankcomm.Bankcomm", "com.yitong.mbank.psbc",
            "cn.com.spdb.mobilebank.per", "com.cib.cibmb"
    };

    private static final String[] STANDARD_LABELS = {
            "微信", "支付宝", "云闪付", "淘宝", "拼多多", "京东", "抖音",
            "工商银行", "建设银行", "农业银行", "中国银行", "招商银行",
            "交通银行", "邮储银行", "浦发银行", "兴业银行"
    };

    // Retained for the existing UI. Capture decisions use private copies, not these mutable arrays.
    public static final String[] KNOWN_PACKAGES = STANDARD_PACKAGES.clone();
    public static final String[] KNOWN_LABELS = STANDARD_LABELS.clone();

    private static final Set<String> DEFAULT_PACKAGES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("com.tencent.mm", "com.eg.android.AlipayGphone")));

    private final SharedPreferences preferences;
    private final SharedPreferences health;
    private final SharedPreferences diagnostics;
    private final Context appContext;
    private static final String CAPTURE_EPOCH = "capture_epoch";
    private static final String PAGE_EPOCH = "page_epoch";
    private static final String PACKAGE_EPOCH = "package_epoch_";
    private static long nextDiagnosticEventId = System.currentTimeMillis();

    public AppSettings(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences("local_ledger_settings", Context.MODE_PRIVATE);
        health = appContext.getSharedPreferences("local_ledger_capture_health", Context.MODE_PRIVATE);
        diagnostics = appContext.getSharedPreferences("local_ledger_capture_diagnostics", Context.MODE_PRIVATE);
    }

    public boolean isCaptureEnabled() {
        return preferences.getBoolean(KEY_CAPTURE_ENABLED, false);
    }

    public void setCaptureEnabled(boolean enabled) {
        synchronized (AppSettings.class) {
            if (isCaptureEnabled() == enabled) return;
            preferences.edit().putBoolean(KEY_CAPTURE_ENABLED, enabled)
                    .putLong(CAPTURE_EPOCH, nextEpoch(CAPTURE_EPOCH)).apply();
        }
        CaptureDispatcher.pruneUnauthorized(appContext);
    }

    public boolean isAccessibilityCaptureEnabled() {
        return preferences.getBoolean(KEY_ACCESSIBILITY_ENABLED, false);
    }

    public void setAccessibilityCaptureEnabled(boolean enabled) {
        synchronized (AppSettings.class) {
            if (isAccessibilityCaptureEnabled() == enabled) return;
            preferences.edit().putBoolean(KEY_ACCESSIBILITY_ENABLED, enabled)
                    .putLong(PAGE_EPOCH, nextEpoch(PAGE_EPOCH)).apply();
        }
        CaptureDispatcher.pruneUnauthorized(appContext);
    }

    public boolean isPackageEnabled(String packageName) {
        return packageName != null && getEnabledPackages().contains(packageName);
    }

    public void setPackageEnabled(String packageName, boolean enabled) {
        if (!isAllowedPackageName(packageName)) return;
        synchronized (AppSettings.class) {
            Set<String> selected = new LinkedHashSet<>(getEnabledPackages());
            if (selected.contains(packageName) == enabled) return;
            if (enabled) selected.add(packageName);
            else selected.remove(packageName);
            preferences.edit().putStringSet(KEY_ENABLED_PACKAGES, selected)
                    .putLong(PACKAGE_EPOCH + packageName, nextEpoch(PACKAGE_EPOCH + packageName)).apply();
        }
        CaptureDispatcher.pruneUnauthorized(appContext);
    }

    public Set<String> getEnabledPackages() {
        Set<String> stored = preferences.getStringSet(KEY_ENABLED_PACKAGES, DEFAULT_PACKAGES);
        Set<String> selected = new LinkedHashSet<>();
        for (String packageName : stored == null ? DEFAULT_PACKAGES : stored) {
            if (isAllowedPackageName(packageName)) selected.add(packageName);
        }
        return Collections.unmodifiableSet(selected);
    }

    public static String getPackageLabel(String packageName) {
        for (int i = 0; i < STANDARD_PACKAGES.length; i++) {
            if (STANDARD_PACKAGES[i].equals(packageName)) return STANDARD_LABELS[i];
        }
        return packageName == null ? "未知应用" : packageName;
    }

    public boolean isBankPackage(String packageName) {
        if (packageName == null) return false;
        for (int i = 0; i < STANDARD_PACKAGES.length; i++) {
            if (STANDARD_PACKAGES[i].equals(packageName)) return i >= 7;
        }
        return preferences.getBoolean("bank_package_" + packageName, false);
    }

    /** Explicitly mark a custom package as a bank; arbitrary apps are never assumed to be banks. */
    public void setBankPackage(String packageName, boolean bank) {
        if (!isAllowedPackageName(packageName)) return;
        for (String known : STANDARD_PACKAGES) if (known.equals(packageName)) return;
        synchronized (AppSettings.class) {
            if (isBankPackage(packageName) == bank) return;
            preferences.edit().putBoolean("bank_package_" + packageName, bank)
                    .putLong(PACKAGE_EPOCH + packageName, nextEpoch(PACKAGE_EPOCH + packageName)).apply();
        }
        CaptureDispatcher.pruneUnauthorized(appContext);
    }

    /** Bank and unknown-app rules currently cover notifications, so their screens are never inspected. */
    public boolean isAccessibilityPackageEnabled(String packageName) {
        if (!isPackageEnabled(packageName)) return false;
        for (int i = 0; i < 7; i++) if (STANDARD_PACKAGES[i].equals(packageName)) return true;
        return false;
    }

    public Set<String> getAccessibilityPackages() {
        Set<String> result = new LinkedHashSet<>();
        for (String packageName : getEnabledPackages()) {
            if (isAccessibilityPackageEnabled(packageName)) result.add(packageName);
        }
        return Collections.unmodifiableSet(result);
    }

    public long getCaptureIssueCount() {
        return health.getLong("issue_count", 0);
    }

    public long getLastCaptureIssueAt() {
        return health.getLong("last_issue_at", 0);
    }

    public String getLastCaptureIssueCode() {
        return health.getString("last_issue_code", "");
    }

    public void clearCaptureIssues() {
        synchronized (AppSettings.class) {
            health.edit().clear().apply();
        }
    }

    /** Fixed local pipeline facts only. A callback or parsed candidate is not a successful ledger entry. */
    public static final class CaptureDiagnostic {
        public final String stage;
        public final String reason;
        public final String sourceLabel;
        public final long observedAt;
        public final long updatedAt;
        public final long eventCount;
        public final String lastResultStage;
        public final String lastResultReason;
        public final long lastResultAt;
        public final String lastMatchStage;
        public final String lastMatchReason;
        public final long lastMatchAt;
        public final int nodeCount;
        public final int visibleNodeCount;
        public final int textNodeCount;
        public final int webViewCount;
        public final int sensitiveNodeCount;
        public final boolean rootVisible;
        public final long structureObservedAt;
        public final String templateShape;
        public final String parsedDirection;
        public final boolean merchantRecognized;
        public final boolean transactionIdFound;
        public final long templateAt;
        public final long readModeAt;
        public final boolean includeContainersRequested;
        public final boolean includeContainersActive;
        public final int rootChildCount;
        public final boolean rootImportant;
        public final boolean eventWindowMatchesRoot;
        public final long notificationMetadataAt;
        public final boolean titleSupported;
        public final boolean messageCategory;
        public final String notificationTemplateKind;
        public final boolean hasMessages;
        public final boolean hasHistoric;
        public final boolean hasRemoteInput;

        private CaptureDiagnostic(SharedPreferences stored, String prefix) {
            stage = stored.getString(prefix + "stage", "idle");
            reason = stored.getString(prefix + "reason", "not_received");
            sourceLabel = stored.getString(prefix + "source", "");
            observedAt = stored.getLong(prefix + "observed", 0);
            updatedAt = stored.getLong(prefix + "updated", 0);
            eventCount = stored.getLong(prefix + "count", 0);
            lastResultStage = stored.getString(prefix + "result_stage", "idle");
            lastResultReason = stored.getString(prefix + "result_reason", "not_processed");
            lastResultAt = stored.getLong(prefix + "result_at", 0);
            lastMatchStage = stored.getString(prefix + "match_stage", "idle");
            lastMatchReason = stored.getString(prefix + "match_reason", "not_processed");
            lastMatchAt = stored.getLong(prefix + "match_at", 0);
            nodeCount = stored.getInt(prefix + "nodes", 0);
            visibleNodeCount = stored.getInt(prefix + "visible_nodes", 0);
            textNodeCount = stored.getInt(prefix + "text_nodes", 0);
            webViewCount = stored.getInt(prefix + "webview_nodes", 0);
            sensitiveNodeCount = stored.getInt(prefix + "sensitive_nodes", 0);
            rootVisible = stored.getBoolean(prefix + "root_visible", false);
            structureObservedAt = stored.getLong(prefix + "structure_at", 0);
            templateShape = safeTemplateShape(stored.getString(prefix + "template_shape", ""));
            parsedDirection = safeParsedDirection(stored.getString(prefix + "parsed_direction", ""));
            merchantRecognized = stored.getBoolean(prefix + "merchant_recognized", false);
            transactionIdFound = stored.getBoolean(prefix + "transaction_id_found", false);
            templateAt = stored.getLong(prefix + "template_at", 0);
            readModeAt = stored.getLong(prefix + "read_mode_at", 0);
            includeContainersRequested = stored.getBoolean(prefix + "include_containers_requested", false);
            includeContainersActive = stored.getBoolean(prefix + "include_containers_active", false);
            rootChildCount = stored.getInt(prefix + "root_children", -1);
            rootImportant = stored.getBoolean(prefix + "root_important", false);
            eventWindowMatchesRoot = stored.getBoolean(prefix + "event_window_matches_root", false);
            notificationMetadataAt = stored.getLong(prefix + "metadata_at", 0);
            titleSupported = stored.getBoolean(prefix + "title_supported", false);
            messageCategory = stored.getBoolean(prefix + "message_category", false);
            notificationTemplateKind = safeNotificationTemplateKind(stored.getString(prefix + "template_kind", "none"));
            hasMessages = stored.getBoolean(prefix + "has_messages", false);
            hasHistoric = stored.getBoolean(prefix + "has_historic", false);
            hasRemoteInput = stored.getBoolean(prefix + "has_remote_input", false);
        }
    }

    public CaptureDiagnostic getNotificationDiagnostic() {
        synchronized (AppSettings.class) {
            return new CaptureDiagnostic(diagnostics, "notification_");
        }
    }

    public CaptureDiagnostic getAccessibilityDiagnostic() {
        synchronized (AppSettings.class) {
            return new CaptureDiagnostic(diagnostics, "accessibility_");
        }
    }

    public void clearCaptureDiagnostics() {
        synchronized (AppSettings.class) {
            diagnostics.edit().clear().apply();
        }
    }

    long beginCaptureDiagnostic(String kind, String packageName) {
        String prefix = diagnosticPrefix(kind);
        synchronized (AppSettings.class) {
            long count = diagnostics.getLong(prefix + "count", 0);
            count = count == Long.MAX_VALUE ? count : count + 1;
            long eventId = ++nextDiagnosticEventId;
            long now = System.currentTimeMillis();
            diagnostics.edit().putLong(prefix + "count", count).putLong(prefix + "event_id", eventId)
                    .putLong(prefix + "observed", now)
                    .putLong(prefix + "updated", now).putString(prefix + "source", diagnosticSourceLabel(packageName))
                    .putString(prefix + "stage", "received").putString(prefix + "reason", "event_received").apply();
            return eventId;
        }
    }

    void updateCaptureDiagnostic(String kind, long eventNumber, String stage, String reason) {
        String prefix = diagnosticPrefix(kind);
        synchronized (AppSettings.class) {
            // An older asynchronous write must not hide the reason for a newer callback.
            if (eventNumber > 0 && diagnostics.getLong(prefix + "event_id", 0) != eventNumber) return;
            diagnostics.edit().putLong(prefix + "updated", System.currentTimeMillis())
                    .putString(prefix + "stage", safeDiagnosticStage(stage))
                    .putString(prefix + "reason", safeDiagnosticReason(reason)).apply();
        }
    }

    void recordCaptureResult(String kind, long eventNumber, String stage, String reason) {
        String prefix = diagnosticPrefix(kind);
        synchronized (AppSettings.class) {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor edit = diagnostics.edit().putLong(prefix + "result_at", now)
                    .putString(prefix + "result_stage", safeDiagnosticStage(stage))
                    .putString(prefix + "result_reason", safeDiagnosticReason(reason));
            // Transitional frames must not hide the most recent actual ledger outcome.
            // A duplicate does not claim that a new pending entry exists.
            if ("pending".equals(stage) || "recorded".equals(stage) || "duplicate".equals(stage)) {
                edit.putLong(prefix + "match_at", now)
                        .putString(prefix + "match_stage", safeDiagnosticStage(stage))
                        .putString(prefix + "match_reason", safeDiagnosticReason(reason));
            }
            if (eventNumber > 0 && diagnostics.getLong(prefix + "event_id", 0) == eventNumber) {
                edit.putLong(prefix + "updated", now).putString(prefix + "stage", safeDiagnosticStage(stage))
                        .putString(prefix + "reason", safeDiagnosticReason(reason));
            }
            edit.apply();
        }
    }

    /** Fixed rule metadata only; all fields belong to one current, still-authorized queued event. */
    void recordRuleDetails(String packageName, String kind, long eventNumber, String consentToken,
                           String shape, String direction, boolean merchantRecognized,
                           boolean transactionIdFound) {
        String prefix = diagnosticPrefix(kind);
        String safeShape = safeTemplateShape(shape);
        if (shape == null || !safeShape.equals(shape)) return;
        synchronized (AppSettings.class) {
            if (!isCaptureEnabled() || !isPackageEnabled(packageName) || eventNumber <= 0
                    || consentToken == null || !consentToken.equals(authorizationToken(packageName, kind))
                    || diagnostics.getLong(prefix + "event_id", 0) != eventNumber) return;
            if (PaymentEvent.ACCESSIBILITY.equals(kind)) {
                if (!isAccessibilityCaptureEnabled() || !isAccessibilityPackageEnabled(packageName)) return;
            } else if (PaymentEvent.BANK_NOTIFICATION.equals(kind)) {
                if (!isBankPackage(packageName)) return;
            } else if (!PaymentEvent.NOTIFICATION.equals(kind)) {
                return;
            }
            diagnostics.edit().putString(prefix + "template_shape", safeShape)
                    .putString(prefix + "parsed_direction", safeParsedDirection(direction))
                    .putBoolean(prefix + "merchant_recognized", merchantRecognized)
                    .putBoolean(prefix + "transaction_id_found", transactionIdFound)
                    .putLong(prefix + "template_at", System.currentTimeMillis()).apply();
        }
    }

    /** Structural counts only, from the same bounded and authorized traversal used for capture. */
    void recordAccessibilityStructure(String packageName, long eventNumber, int nodeCount,
                                      int visibleNodeCount, int textNodeCount, int webViewCount,
                                      int sensitiveNodeCount, boolean rootVisible) {
        String prefix = diagnosticPrefix(PaymentEvent.ACCESSIBILITY);
        synchronized (AppSettings.class) {
            if (!isCaptureEnabled() || !isAccessibilityCaptureEnabled()
                    || !isAccessibilityPackageEnabled(packageName) || eventNumber <= 0
                    || diagnostics.getLong(prefix + "event_id", 0) != eventNumber) return;
            int nodes = Math.max(0, Math.min(200, nodeCount));
            diagnostics.edit().putInt(prefix + "nodes", nodes)
                    .putInt(prefix + "visible_nodes", Math.max(0, Math.min(nodes, visibleNodeCount)))
                    .putInt(prefix + "text_nodes", Math.max(0, Math.min(nodes, textNodeCount)))
                    .putInt(prefix + "webview_nodes", Math.max(0, Math.min(nodes, webViewCount)))
                    .putInt(prefix + "sensitive_nodes", Math.max(0, Math.min(nodes, sensitiveNodeCount)))
                    .putBoolean(prefix + "root_visible", rootVisible)
                    .putLong(prefix + "structure_at", System.currentTimeMillis()).apply();
        }
    }

    /** WeChat metadata only; no window IDs, node IDs, titles or body text. */
    void recordAccessibilityReadMode(String packageName, long eventNumber, boolean requested,
                                     boolean active, int rootChildren, boolean important,
                                     boolean windowMatches) {
        String prefix = "accessibility_";
        synchronized (AppSettings.class) {
            if (!"com.tencent.mm".equals(packageName) || !isCaptureEnabled() || !isAccessibilityCaptureEnabled()
                    || !isAccessibilityPackageEnabled(packageName) || eventNumber <= 0
                    || diagnostics.getLong(prefix + "event_id", 0) != eventNumber) return;
            diagnostics.edit().putLong(prefix + "read_mode_at", System.currentTimeMillis())
                    .putBoolean(prefix + "include_containers_requested", requested)
                    .putBoolean(prefix + "include_containers_active", active)
                    .putInt(prefix + "root_children", Math.max(-1, Math.min(200, rootChildren)))
                    .putBoolean(prefix + "root_important", important)
                    .putBoolean(prefix + "event_window_matches_root", windowMatches).apply();
        }
    }

    /** Fixed notification template facts, inspected before deciding whether to read the body. */
    void recordNotificationMetadata(String packageName, long eventNumber, boolean supportedTitle,
                                    boolean categoryMessage, String templateKind, boolean messages,
                                    boolean historic, boolean remoteInput) {
        String prefix = "notification_";
        synchronized (AppSettings.class) {
            if (!"com.tencent.mm".equals(packageName) || !isCaptureEnabled() || !isPackageEnabled(packageName)
                    || eventNumber <= 0 || diagnostics.getLong(prefix + "event_id", 0) != eventNumber) return;
            diagnostics.edit().putLong(prefix + "metadata_at", System.currentTimeMillis())
                    .putBoolean(prefix + "title_supported", supportedTitle)
                    .putBoolean(prefix + "message_category", categoryMessage)
                    .putString(prefix + "template_kind", safeNotificationTemplateKind(templateKind))
                    .putBoolean(prefix + "has_messages", messages)
                    .putBoolean(prefix + "has_historic", historic)
                    .putBoolean(prefix + "has_remote_input", remoteInput).apply();
        }
    }

    private static String safeNotificationTemplateKind(String value) {
        switch (value == null ? "" : value) {
            case "messages": case "big_text": case "inbox": case "other": case "none": return value;
            default: return "other";
        }
    }

    public static String describeDiagnosticStage(String stage) {
        if (stage == null) return "未知阶段";
        switch (stage) {
            case "idle": return "尚无事件";
            case "ready": return "服务已连接";
            case "received": return "已收到事件";
            case "filtered": return "采集前已忽略";
            case "scheduled": return "等待读取页面";
            case "queued": return "已加入本机处理队列";
            case "processing": return "正在本机识别";
            case "ignored": return "未生成新账目";
            case "pending": return "已生成待确认记录";
            case "recorded": return "已自动入账";
            case "duplicate": return "已处理过该事件";
            case "failed": return "处理异常";
            case "paused": return "识别已暂停";
            case "disconnected": return "服务已断开";
            default: return "未知阶段";
        }
    }

    public static String describeDiagnosticReason(String reason) {
        if (reason == null) return "未知原因";
        switch (reason) {
            case "not_received": return "尚未收到所选应用的事件";
            case "not_processed": return "尚无规则处理结果";
            case "service_ready": return "系统服务已连接，等待所选应用的新事件";
            case "capture_disabled": return "自动识别开关未开启";
            case "page_capture_disabled": return "付款页面识别开关未开启";
            case "no_selected_sources": return "没有选择支持此通道的来源";
            case "service_disconnected": return "系统已断开识别服务";
            case "service_interrupted": return "系统暂时中断页面识别";
            case "event_received": return "收到所选来源的事件，尚不代表已识别付款";
            case "no_rule": return "此来源尚无识别规则";
            case "group_summary": return "通知组摘要不是单笔交易";
            case "message_notification": return "消息或可回复通知已按隐私规则忽略";
            case "title_too_long": return "通知标题超过长度限制";
            case "title_unsupported": return "通知标题不符合已支持的付款模板";
            case "metadata_unavailable": return "通知或页面元数据暂不可读";
            case "body_limit": return "通知文字超过限制，未截断解析";
            case "empty_text": return "没有可读取的文字";
            case "root_not_visible": return "根节点未标记为可见，同包子树也未提供可见节点";
            case "tree_no_visible_text": return "已遍历可见节点，但没有可用于识别的文字或内容描述";
            case "sensitive_page": return "页面含系统标记的敏感节点，已整页忽略";
            case "package_mismatch": return "页面节点来源不明或属于其他应用，已整页忽略";
            case "same_event": return "相同通知事件已处理过";
            case "chat_window": return "聊天页面已按隐私规则忽略";
            case "input_present": return "页面含输入框或密码字段，已整页忽略";
            case "node_limit": return "页面节点超过限制，未截断解析";
            case "text_limit": return "页面文字超过限制，未截断解析";
            case "incomplete_tree": return "页面节点不完整，已放弃本次读取";
            case "window_unavailable": return "当前页面不可访问或受系统保护";
            case "window_changed": return "前台已切换，未读取其他应用";
            case "same_screen": return "本次页面文字及顶部排列与上一份相同，未重复提交";
            case "capture_scheduled": return "已收到页面事件，等待短暂稳定后读取";
            case "queue_accepted": return "已排队，尚不代表入账";
            case "queue_full": return "处理队列已满，本次未接收";
            case "in_flight": return "相同事件已在队列中处理";
            case "processing": return "正在运行本机规则";
            case "consent_changed": return "授权或来源选择已改变，本次已取消";
            case "rule_not_matched": return "本地规则未匹配交易；可能缺少成功状态、可识别金额或模板尚不支持";
            case "saved_pending": return "已保存到待确认，请查看待确认页";
            case "merchant_supplemented": return "已为已有待核对记录补充商户，未新增记录";
            case "saved_recorded": return "已保存为自动入账记录";
            case "already_recorded": return "同一采集事件或交易已处理，未新增";
            case "write_failed": return "本机处理或写入失败，请检查账本";
            case "processed": return "本机处理已完成，请检查账本与待确认";
            default: return "未知原因";
        }
    }

    private static String safeDiagnosticStage(String value) {
        switch (value == null ? "" : value) {
            case "idle": case "ready": case "received": case "filtered": case "scheduled": case "queued":
            case "processing": case "ignored": case "pending": case "recorded": case "duplicate":
            case "failed": case "paused": case "disconnected": return value;
            default: return "ignored";
        }
    }

    private static String safeDiagnosticReason(String value) {
        // Only enumerated codes may be persisted; never write a notification body or exception message.
        if (value != null && !"未知原因".equals(describeDiagnosticReason(value))) return value;
        return "rule_not_matched";
    }

    private static String safeParsedDirection(String value) {
        switch (value == null ? "" : value) {
            case "expense": case "income": case "refund": case "transfer": return value;
            default: return "";
        }
    }

    private static String safeTemplateShape(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() > 800 || !value.matches("[a-z_]+(?:,[a-z_]+)*")) return "";
        String[] tokens = value.split(",", -1);
        if (tokens.length > 48) return "";
        for (String token : tokens) {
            switch (token) {
                case "title": case "navigation": case "success": case "success_text":
                case "currency": case "merchant_label": case "time_label": case "id_label":
                case "field_label": case "time_value": case "amount": case "amount_signed":
                case "identifier": case "name": case "other":
                case "awaiting_receipt": case "refund_action": case "payment_time_label":
                case "created_time_label": case "payment_method_label": case "paid_amount_label":
                case "parse_missing_data": case "parse_source": case "parse_notification_title":
                case "parse_unpaid_or_failed": case "parse_advertisement": case "parse_foreign":
                case "parse_signed_amount": case "parse_status": case "parse_shop_status": case "parse_amount":
                case "merchant_labeled": case "merchant_not_applicable": case "header_not_found":
                case "header_amount_mismatch": case "header_embedded_amount": case "header_invalid_name":
                case "header_conflict": case "header_found":
                case "spatial_header": case "expense_amount_summary": case "header_name_is_label":
                case "header_begin": case "header_end": case "header_letter": case "header_digit":
                case "header_space": case "header_minus": case "header_currency": case "header_dot":
                case "header_comma": case "header_colon": case "header_slash": case "header_left":
                case "header_right": case "header_other": break;
                default: return "";
            }
        }
        return value;
    }

    private String diagnosticSourceLabel(String packageName) {
        for (int i = 0; i < STANDARD_PACKAGES.length; i++) {
            if (STANDARD_PACKAGES[i].equals(packageName)) return STANDARD_LABELS[i];
        }
        return packageName == null ? "" : isBankPackage(packageName) ? "自选银行" : "其他已选来源";
    }

    private static String diagnosticPrefix(String kind) {
        return PaymentEvent.ACCESSIBILITY.equals(kind) ? "accessibility_" : "notification_";
    }

    void recordCaptureIssue(String code) {
        if (!CAPTURE_ISSUE_QUEUE_FULL.equals(code) && !CAPTURE_ISSUE_WRITE_FAILED.equals(code)
                && !CAPTURE_ISSUE_SOURCE_UNAVAILABLE.equals(code)) return;
        synchronized (AppSettings.class) {
            long count = getCaptureIssueCount();
            health.edit().putLong("issue_count", count == Long.MAX_VALUE ? count : count + 1)
                    .putLong("last_issue_at", System.currentTimeMillis()).putString("last_issue_code", code).apply();
        }
    }

    /** Queued captures belong to the consent episode in which they were collected. */
    String authorizationToken(String packageName, String sourceKind) {
        synchronized (AppSettings.class) {
            return preferences.getLong(CAPTURE_EPOCH, 0) + ":"
                    + preferences.getLong(PACKAGE_EPOCH + packageName, 0) + ":"
                    + (PaymentEvent.ACCESSIBILITY.equals(sourceKind) ? preferences.getLong(PAGE_EPOCH, 0) : 0);
        }
    }

    private long nextEpoch(String key) {
        long current = preferences.getLong(key, 0);
        return current == Long.MAX_VALUE ? 0 : current + 1;
    }

    private boolean isAllowedPackageName(String packageName) {
        return isValidPackageName(packageName) && !appContext.getPackageName().equals(packageName);
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private static boolean isValidPackageName(String packageName) {
        return packageName != null && packageName.length() <= 240
                && packageName.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
}
