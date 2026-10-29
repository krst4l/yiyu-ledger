package dev.mr.localledger;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;
import java.util.Locale;

/** Public system settings and a user-shareable report without payment or account data. */
public final class DeviceCompatibility {
    private static final int UNKNOWN = -1;
    private static final int DISABLED = 0;
    private static final int ENABLED = 1;

    private DeviceCompatibility() { }

    public static String deviceSummary() {
        return clean(Build.BRAND) + " / " + clean(Build.MODEL);
    }

    /** These are navigation hints; vendors change menu names between system versions. */
    public static String backgroundGuidance() {
        String identity = (safe(Build.MANUFACTURER) + " " + safe(Build.BRAND))
                .toLowerCase(Locale.ROOT);
        String vendorGuide;
        if (containsAny(identity, "xiaomi", "redmi", "poco")) {
            vendorGuide = "小米 / Redmi / POCO：在应用信息中检查“省电策略”，按需选择“无限制”；"
                    + "在系统设置中搜索“自启动”，允许粥粥记账自启动。";
        } else if (containsAny(identity, "oppo", "oneplus", "realme")) {
            vendorGuide = "OPPO / 一加 / realme：在应用信息中查找“耗电管理”或“电池用量”，"
                    + "按需允许后台活动；在系统设置中搜索“自启动”，若有对应开关则允许。";
        } else if (containsAny(identity, "vivo", "iqoo")) {
            vendorGuide = "vivo / iQOO：在系统设置中搜索“后台耗电管理”，按需允许后台高耗电；"
                    + "在应用管理或 i 管家中检查“自启动”权限。";
        } else if (containsAny(identity, "honor")) {
            vendorGuide = "荣耀：在系统设置中搜索“应用启动管理”，若有手动管理选项，"
                    + "按需允许自启动、关联启动和后台活动。";
        } else if (containsAny(identity, "huawei")) {
            vendorGuide = "华为（支持 Android APK 的系统）：在系统设置中搜索“应用启动管理”，"
                    + "若有手动管理选项，按需允许自启动、关联启动和后台活动。"
                    + "原生鸿蒙系统不支持安装本应用的 Android APK。";
        } else if (containsAny(identity, "samsung")) {
            vendorGuide = "三星：在“电池 → 后台使用限制”中检查休眠应用列表，"
                    + "避免将粥粥记账放入休眠或深度休眠；应用信息中若有电池限制选项，可按需调整。";
        } else if (containsAny(identity, "google", "pixel")) {
            vendorGuide = "Pixel / 原生 Android：在应用信息的“应用电池用量”中检查“允许后台使用”，"
                    + "若有“不受限制”选项，可按需调整。";
        } else {
            vendorGuide = "请在应用信息中检查“电池”或“后台运行”，并在系统设置中搜索“自启动”。"
                    + "摩托罗拉、联想及其他品牌的入口名称可能不同。";
        }
        return vendorGuide + "\n\n菜单名称因系统版本和地区而异，找不到时可使用上方的通用设置入口。"
                + "允许后台运行可能增加耗电，不能保证系统始终保留服务。"
                + "\n\n如最近任务支持“锁定”，可按需锁定粥粥记账，避免一键清理。"
                + "清后台或强行停止后，请回到应用检查通知使用权和无障碍授权；"
                + "应用不会自行恢复被关闭的系统授权。"
                + "\n\nAndroid 13 及以上从文件安装后，若系统提示“受限设置”，"
                + "请先确认 APK 来源，在应用信息菜单中查找“允许受限设置”，再手动授权。"
                + "没有此提示则无需操作。";
    }

    public static boolean notificationAccess(Context context) {
        return notificationGrant(context) == ENABLED;
    }

    public static boolean accessibilityAccess(Context context) {
        return accessibilityGrant(context) == ENABLED;
    }

    public static String notificationStatus(Context context) {
        return grantLabel(notificationGrant(context));
    }

    public static String accessibilityStatus(Context context) {
        return grantLabel(accessibilityGrant(context));
    }

    public static String batteryStatus(Context context) {
        int status = batteryExemption(context);
        if (status == ENABLED) return "已允许不受电池优化限制";
        if (status == DISABLED) return "仍受电池优化限制";
        return "无法查询";
    }

    /** Opens the public optimization list, without requesting a direct exemption. */
    public static boolean openBatterySettings(Context context) {
        return openFirst(context,
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetails(context), new Intent(Settings.ACTION_SETTINGS));
    }

    public static boolean openAppSettings(Context context) {
        return openFirst(context, appDetails(context), new Intent(Settings.ACTION_SETTINGS));
    }

    public static boolean openAccessibilitySettings(Context context) {
        return openFirst(context, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                appDetails(context), new Intent(Settings.ACTION_SETTINGS));
    }

    public static boolean openNotificationSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(context, LedgerNotificationService.class)
                                    .flattenToString());
            return openFirst(context, detail,
                    new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    appDetails(context), new Intent(Settings.ACTION_SETTINGS));
        }
        return openFirst(context, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                appDetails(context), new Intent(Settings.ACTION_SETTINGS));
    }

    /** Generated locally; callers preview/copy it only after the user's explicit action. */
    public static String createReport(Context context, AppSettings settings) {
        return "粥粥记账兼容性信息\n"
                + "品牌：" + clean(Build.BRAND) + "\n"
                + "制造商：" + clean(Build.MANUFACTURER) + "\n"
                + "型号：" + clean(Build.MODEL) + "\n"
                + "Android：" + clean(Build.VERSION.RELEASE)
                + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "粥粥记账版本：" + packageVersion(context, context.getPackageName()) + "\n"
                + "微信版本：" + packageVersion(context, "com.tencent.mm") + "\n"
                + "支付宝版本：" + packageVersion(context, "com.eg.android.AlipayGphone") + "\n"
                + "自动识别开关：" + enabledLabel(settings.isCaptureEnabled()) + "\n"
                + "付款页面识别开关：" + enabledLabel(settings.isAccessibilityCaptureEnabled()) + "\n"
                + "微信来源选择：" + enabledLabel(settings.isPackageEnabled("com.tencent.mm")) + "\n"
                + "支付宝来源选择：" + enabledLabel(settings.isPackageEnabled("com.eg.android.AlipayGphone")) + "\n"
                + "通知使用权：" + grantLabel(notificationGrant(context)) + "\n"
                + "无障碍授权：" + grantLabel(accessibilityGrant(context)) + "\n"
                + "系统电池优化：" + batteryStatus(context) + "\n"
                + "\n授权状态不代表服务已连接，也不代表能成功识别。"
                + "电池状态不包含厂商额外的后台限制。\n"
                + "此信息不包含账单、通知正文、账号或设备序列号。"
                + "请另行填写现象和复现步骤。";
    }

    private static int notificationGrant(Context context) {
        ComponentName component = new ComponentName(context, LedgerNotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                if (manager != null) {
                    return manager.isNotificationListenerAccessGranted(component) ? ENABLED : DISABLED;
                }
            } catch (RuntimeException ignored) {
                // Some systems limit this query; the legacy exact-component check may still work.
            }
        }
        try {
            String listeners = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (listeners != null) {
                for (String flattened : listeners.split(":")) {
                    if (component.equals(ComponentName.unflattenFromString(flattened))) return ENABLED;
                }
            }
            return DISABLED;
        } catch (RuntimeException ignored) {
            return UNKNOWN;
        }
    }

    private static int accessibilityGrant(Context context) {
        try {
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            if (manager == null) return UNKNOWN;
            List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (services == null) return UNKNOWN;
            for (AccessibilityServiceInfo service : services) {
                if (service == null) continue;
                ResolveInfo info = service.getResolveInfo();
                if (info != null && info.serviceInfo != null
                        && context.getPackageName().equals(info.serviceInfo.packageName)
                        && PaymentAccessibilityService.class.getName().equals(info.serviceInfo.name)) {
                    return ENABLED;
                }
            }
            return DISABLED;
        } catch (RuntimeException ignored) {
            return UNKNOWN;
        }
    }

    private static int batteryExemption(Context context) {
        try {
            PowerManager manager = context.getSystemService(PowerManager.class);
            if (manager == null) return UNKNOWN;
            return manager.isIgnoringBatteryOptimizations(context.getPackageName()) ? ENABLED : DISABLED;
        } catch (RuntimeException ignored) {
            return UNKNOWN;
        }
    }

    private static String packageVersion(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return clean(info.versionName) + "（" + code + "）";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "未安装或当前用户不可见";
        } catch (RuntimeException ignored) {
            return "无法查询";
        }
    }

    private static Intent appDetails(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
    }

    private static boolean openFirst(Context context, Intent... intents) {
        for (Intent intent : intents) {
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                // Attempt the public intent directly; package visibility can hide resolveActivity results.
                context.startActivity(intent);
                return true;
            } catch (RuntimeException ignored) {
                // A vendor may omit or restrict an entry. Try the next public settings destination.
            }
        }
        return false;
    }

    private static String grantLabel(int status) {
        return status == ENABLED ? "已授权" : status == DISABLED ? "未授权" : "无法查询";
    }

    private static String enabledLabel(boolean enabled) {
        return enabled ? "开启" : "关闭";
    }

    private static boolean containsAny(String value, String... names) {
        for (String name : names) if (value.contains(name)) return true;
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        if (value == null || value.trim().isEmpty()) return "未知";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 96; i++) {
            char character = value.charAt(i);
            result.append(Character.isISOControl(character) ? ' ' : character);
        }
        return result.toString().trim();
    }
}
