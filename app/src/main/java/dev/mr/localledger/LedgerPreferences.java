package dev.mr.localledger;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Local UI preferences. Budgets and custom category names never leave this device. */
public final class LedgerPreferences {
    private static final long MAX_AMOUNT_FEN = 999_999_999_99L;
    private final SharedPreferences preferences;
    public LedgerPreferences(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("ledger_ui", Context.MODE_PRIVATE);
    }
    public boolean lootEnabled() { return preferences.getBoolean("loot_enabled", true); }
    public void setLootEnabled(boolean enabled) {
        preferences.edit().putBoolean("loot_enabled", enabled).apply();
    }
    /** One of three original operator appearances; unrelated to any game character. */
    public int operatorStyle() {
        int value;
        try { value = preferences.getInt("operator_style", 0); }
        catch (ClassCastException malformed) { return 0; }
        return value >= 0 && value <= 2 ? value : 0;
    }
    public void setOperatorStyle(int style) {
        if (style < 0 || style > 2) throw new IllegalArgumentException("Invalid operator style");
        preferences.edit().putInt("operator_style", style).apply();
    }
    /** RMB reference in fen, used only for optional visual equivalents. */
    public long lootReferenceFen(String id, long fallback) {
        validateLootId(id);
        if (fallback < 0 || fallback > MAX_AMOUNT_FEN) throw new IllegalArgumentException("Invalid reference fallback");
        long value;
        try { value = preferences.getLong("loot_reference_" + id, fallback); }
        catch (ClassCastException malformed) { return fallback; }
        return value > 0 && value <= MAX_AMOUNT_FEN ? value : fallback;
    }
    public void setLootReferenceFen(String id, long fen) {
        validateLootId(id);
        validateLootReference(fen);
        preferences.edit().putLong("loot_reference_" + id, fen).apply();
    }
    private static void validateLootId(String id) {
        if (id == null || !id.matches("[a-z0-9_]{1,40}")) throw new IllegalArgumentException("Invalid reference item");
    }
    private static void validateLootReference(long fen) {
        if (fen <= 0 || fen > MAX_AMOUNT_FEN) throw new IllegalArgumentException("参考金额应大于 0 且不超过 999999999.99 元");
    }
    public long monthlyBudget(int year, int month) { return preferences.getLong("budget_" + year + "_" + month, 0); }
    public void setMonthlyBudget(int year, int month, long fen) {
        if (fen < 0 || fen > 999_999_999_99L) throw new IllegalArgumentException("Invalid budget");
        preferences.edit().putLong("budget_" + year + "_" + month, fen).apply();
    }
    public List<String> categories() {
        List<String> names = new ArrayList<>(Arrays.asList("餐饮", "购物", "交通", "居家", "娱乐", "医疗", "教育", "人情", "旅行", "数码", "其他"));
        try {
            JSONArray extra = new JSONArray(preferences.getString("categories", "[]"));
            for (int i = 0; i < extra.length() && i < 50; i++) {
                String name = extra.optString(i).trim();
                if (!name.isEmpty() && name.length() <= 60 && !names.contains(name)) names.add(name);
            }
        } catch (Exception ignored) { /* Keep the default categories if local preferences are malformed. */ }
        return names;
    }
    public void addCategory(String value) {
        String name = value.trim();
        if (name.isEmpty() || name.length() > 60 || name.matches(".*[\\p{Cntrl}].*")) throw new IllegalArgumentException("分类名称应为 1 至 60 个字符");
        if (categories().contains(name)) return;
        JSONArray extra;
        try { extra = new JSONArray(preferences.getString("categories", "[]")); }
        catch (Exception ignored) { extra = new JSONArray(); }
        if (extra.length() >= 50) throw new IllegalArgumentException("自定义分类最多 50 个");
        extra.put(name);
        preferences.edit().putString("categories", extra.toString()).apply();
    }
}
