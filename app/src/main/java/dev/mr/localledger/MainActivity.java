package dev.mr.localledger;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(241, 242, 236);
    private static final int BLUE = Color.rgb(227, 91, 83);
    private static final int MUTED = Color.rgb(154, 168, 158);
    private static final int PAPER = Color.rgb(16, 20, 18);
    private static final int SURFACE = Color.rgb(25, 32, 29);
    private static final int TINT = Color.rgb(53, 35, 31);
    private static final int LINE = Color.rgb(48, 58, 52);
    private static final int EXPENSE_COLOR = BLUE;
    private static final int CAUTION = Color.rgb(219, 183, 116);
    private static final int EXPORT_CSV = 71;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat fullTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
    private LinearLayout shell, content;
    private AppSettings settings;
    private LedgerPreferences uiPreferences;
    private LedgerDb db;
    private int selectedTab, generation;
    private QuickEntryDialog editorDialog;
    private Bundle restoredEditor;
    private SaveOperation activeSave;
    private QuickEntryDialog.Completion saveCompletion;
    private AlertDialog savingDialog;
    private Bundle uncertainSaveDraft;
    private String uncertainSaveId;
    private boolean resumed;
    private int selectedYear = Calendar.getInstance().get(Calendar.YEAR);
    private int selectedMonth = Calendar.getInstance().get(Calendar.MONTH) + 1;
    private int selectedDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
    private int visibleLimit = 100;
    private boolean reviewOpen, recognitionOpen, chartIncome;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new AppSettings(this);
        uiPreferences = new LedgerPreferences(this);
        db = new LedgerDb(getApplicationContext());
        if (state != null) {
            selectedTab = Math.max(0, Math.min(3, state.getInt("tab", 0)));
            selectedYear = state.getInt("year", selectedYear);
            selectedMonth = state.getInt("month", selectedMonth);
            selectedDay = state.getInt("day", selectedDay);
            reviewOpen = state.getBoolean("review");
            recognitionOpen = state.getBoolean("recognition");
            chartIncome = state.getBoolean("chart_income");
        }
        if (state != null) {
            restoredEditor = state.getBundle("editor");
            uncertainSaveDraft = state.getBundle("uncertain_save_draft");
            uncertainSaveId = state.getString("uncertain_save_id");
        }
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof RetainedSaveState) {
            RetainedSaveState saved = (RetainedSaveState) retained;
            activeSave = saved.operation;
            restoredEditor = activeSave == null ? saved.editorDraft : null;
            uncertainSaveDraft = saved.uncertainDraft;
            uncertainSaveId = saved.uncertainId;
        } else if (state != null && state.getString("save_operation_id") != null) {
            // The process died: the transaction may already have committed. Never replay it.
            uncertainSaveId = state.getString("save_operation_id");
            uncertainSaveDraft = state.getBundle("save_operation_draft");
            restoredEditor = null;
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(PAPER);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        shell = vertical();
        shell.setBackgroundColor(PAPER);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(shell);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        render();
        if (activeSave != null) {
            activeSave.attach(this);
            if (!activeSave.finished && (editorDialog == null || !editorDialog.isShowing())) {
                savingDialog = new AlertDialog.Builder(this).setTitle("正在保存账目")
                        .setMessage("请稍候，正在完成同一次保存。")
                        .setCancelable(false).show();
            }
            return;
        }
        if (restoredEditor != null) {
            Bundle draft = restoredEditor;
            restoredEditor = null;
            restoreEntryDraft(draft);
        }
    }
    @Override protected void onPause() {
        resumed = false;
        if (activeSave != null) activeSave.detach(this);
        dismissSavingDialog();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", selectedTab);
        out.putInt("year", selectedYear); out.putInt("month", selectedMonth); out.putInt("day", selectedDay);
        out.putBoolean("review", reviewOpen); out.putBoolean("recognition", recognitionOpen); out.putBoolean("chart_income", chartIncome);
        if (activeSave != null) {
            out.putString("save_operation_id", activeSave.id);
            out.putBundle("save_operation_draft", new Bundle(activeSave.draft));
        } else if (editorDialog != null && editorDialog.isShowing()) {
            out.putBundle("editor", editorDialog.snapshot());
        } else if (restoredEditor != null) out.putBundle("editor", new Bundle(restoredEditor));
        if (uncertainSaveDraft != null) {
            out.putString("uncertain_save_id", uncertainSaveId);
            out.putBundle("uncertain_save_draft", new Bundle(uncertainSaveDraft));
        }
        super.onSaveInstanceState(out);
    }
    @Override public Object onRetainNonConfigurationInstance() {
        Bundle draft = restoredEditor;
        if (activeSave == null && editorDialog != null && editorDialog.isShowing()) draft = editorDialog.snapshot();
        return new RetainedSaveState(activeSave, draft, uncertainSaveId, uncertainSaveDraft);
    }
    @Override protected void onDestroy() {
        generation++;
        if (activeSave != null) activeSave.detach(this);
        saveCompletion = null;
        dismissSavingDialog();
        // Services use separate helpers. Close only after this Activity's queued work.
        worker.execute(db::close);
        worker.shutdown();
        if (editorDialog != null) editorDialog.dismiss();
        super.onDestroy();
    }
    private void selectTab(int tab) {
        selectedTab = tab;
        visibleLimit = 100;
        render();
        if (ValueAnimator.areAnimatorsEnabled()) {
            content.setAlpha(0f);
            content.setTranslationY(dp(7));
            content.animate().alpha(1f).translationY(0f).setDuration(180).start();
        }
    }
    @Override public void onBackPressed() {
        if (reviewOpen || recognitionOpen) { reviewOpen = false; recognitionOpen = false; render(); }
        else super.onBackPressed();
    }
    private void render() {
        int token = ++generation;
        shell.removeAllViews();
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22), dp(12), dp(16), dp(8));
        LinearLayout heading = vertical();
        String title = recognitionOpen ? "自动记账" : reviewOpen ? "待核对" : new String[]{"粥粥记账", "账本日历", "收支分析", "我的账本"}[selectedTab];
        heading.addView(text(title, 23, INK, true));
        TextView subtitle = text("ZHOUZHOU  /  PERSONAL LEDGER", 10, MUTED, false);
        subtitle.setTypeface(Typeface.MONOSPACE);
        subtitle.setLetterSpacing(.1f);
        heading.addView(subtitle);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (reviewOpen || recognitionOpen) {
            iconAction(header, "back", "返回", () -> { reviewOpen = false; recognitionOpen = false; render(); });
        } else {
            iconAction(header, "search", "搜索账目", this::searchEntries);
            iconAction(header, "inbox", "打开待核对", () -> { reviewOpen = true; render(); });
        }
        shell.addView(header);
        if (uncertainSaveDraft != null) {
            Button notice = button("上次保存结果待核对 · 处理草稿", false);
            notice.setTextColor(CAUTION);
            notice.setContentDescription("上次保存结果无法确认，请先核对已有记录，避免重复记账。点击处理草稿。");
            notice.setOnClickListener(v -> reviewUncertainSave());
            shell.addView(notice);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = vertical();
        content.setPadding(dp(20), dp(16), dp(20), dp(28));
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderNavigation();
        if (recognitionOpen) { renderSettings(); return; }
        if (selectedTab == 3 && !reviewOpen) { renderProfile(); return; }
        content.addView(text("正在读取账本…", 14, MUTED, false));
        final int year = selectedYear, month = selectedMonth, tab = selectedTab;
        final boolean review = reviewOpen;
        worker.execute(() -> {
            try {
                List<LedgerDb.Entry> entries = review ? db.list(true) : tab == 2 ? new ArrayList<>() : db.listMonth(year, month);
                LedgerDb.Totals totals = db.monthTotals(year, month);
                int pending = db.monthTotals().pendingCount;
                List<LedgerDb.DayTotal> days = review ? new ArrayList<>() : db.dayTotals(year, month);
                List<LedgerDb.CategoryTotal> categories = tab == 2 ? db.categoryTotals(year, month) : new ArrayList<>();
                runOnUiThread(() -> {
                    if (isDestroyed() || token != generation) return;
                    content.removeAllViews();
                    if (review) renderReview(entries, pending);
                    else if (tab == 0) renderHome(entries, totals, pending);
                    else if (tab == 1) renderCalendar(entries, totals, days);
                    else renderAnalysis(totals, days, categories);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || token != generation) return;
                    content.removeAllViews();
                    emptyState(content, "暂时无法读取账本", "请重试，保留应用数据，不要卸载。", "重新读取", this::render);
                });
            }
        });
    }
    private void renderNavigation() {
        LinearLayout bottom = horizontal();
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(10), dp(7), dp(10), dp(8));
        bottom.setBackgroundColor(PAPER);
        String[] icons = {"home", "calendar", "plus", "chart", "user"};
        String[] labels = {"账本", "日历", "记一笔", "统计", "我的"};
        for (int i = 0; i < 5; i++) {
            final int position = i;
            int tab = i < 2 ? i : i - 1;
            boolean active = i != 2 && !reviewOpen && !recognitionOpen && selectedTab == tab;
            LinearLayout item = vertical();
            item.setGravity(Gravity.CENTER);
            item.setBackground(feedback(Color.TRANSPARENT, 6));
            View icon = LedgerUi.icon(this, icons[i], i == 2 ? PAPER : active ? BLUE : MUTED, i == 2 ? BLUE : Color.TRANSPARENT);
            item.addView(icon, new LinearLayout.LayoutParams(dp(i == 2 ? 44 : 26), dp(i == 2 ? 44 : 26)));
            if (i != 2) {
                TextView label = text(labels[i], 10, active ? BLUE : MUTED, active);
                label.setGravity(Gravity.CENTER);
                label.setPadding(0, dp(4), 0, 0);
                item.addView(label);
            }
            item.setContentDescription(labels[i] + (active ? "，已选中" : ""));
            item.setOnClickListener(v -> {
                if (position == 2) { editEntry(null); return; }
                reviewOpen = false; recognitionOpen = false;
                selectTab(position < 2 ? position : position - 1);
            });
            bottom.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        rule(shell);
        shell.addView(bottom);
    }
    private void iconAction(LinearLayout row, String icon, String description, Runnable action) {
        LinearLayout hit = vertical();
        hit.setGravity(Gravity.CENTER);
        View image = LedgerUi.icon(this, icon, BLUE, Color.TRANSPARENT);
        hit.addView(image, new LinearLayout.LayoutParams(dp(23), dp(23)));
        hit.setBackground(feedback(Color.TRANSPARENT, 12));
        hit.setContentDescription(description);
        hit.setOnClickListener(v -> action.run());
        row.addView(hit, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    private void monthNavigation() {
        LinearLayout months = horizontal();
        months.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = button("‹", false);
        previous.setTextSize(25);
        previous.setContentDescription("上个月");
        previous.setOnClickListener(v -> changeMonth(-1));
        months.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button value = button(selectedYear + " 年 " + selectedMonth + " 月  ⌄", false);
        value.setTextColor(INK);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setOnClickListener(v -> chooseMonth());
        months.addView(value, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button next = button("›", false);
        next.setTextSize(25);
        next.setContentDescription("下个月");
        next.setOnClickListener(v -> changeMonth(1));
        months.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(months);
        spacer(content, 14);
    }
    private void changeMonth(int offset) {
        java.time.YearMonth month = java.time.YearMonth.of(selectedYear, selectedMonth).plusMonths(offset);
        if (month.getYear() < 1970 || month.getYear() > 9999) return;
        selectedYear = month.getYear(); selectedMonth = month.getMonthValue();
        selectedDay = Math.min(selectedDay, month.lengthOfMonth()); visibleLimit = 100;
        render();
    }
    private void chooseMonth() {
        new DatePickerDialog(this, (picker, year, month, day) -> {
            if (year < 1970 || year > 9999) return;
            selectedYear = year; selectedMonth = month + 1; selectedDay = day;
            visibleLimit = 100; render();
        }, selectedYear, selectedMonth - 1, selectedDay).show();
    }
    private void renderHome(List<LedgerDb.Entry> entries, LedgerDb.Totals totals, int pending) {
        monthNavigation();
        LinearLayout summary = vertical();
        summary.setBackground(TacticalVisuals.panel(this, true));
        summary.setPadding(dp(18), dp(17), dp(18), dp(17));
        LinearLayout headline = horizontal();
        headline.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout spend = vertical();
        spend.addView(text("本月已确认支出", 12, MUTED, false));
        spacer(spend, 6);
        TextView spent = text("¥ " + money(totals.expenseFen), 36, INK, true);
        spent.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        spent.setMaxLines(1);
        spent.setAutoSizeTextTypeUniformWithConfiguration(12, 36, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        spend.addView(spent, new LinearLayout.LayoutParams(-1, dp(56)));
        LootCatalog.Match equivalent = LootCatalog.match(totals.expenseFen, uiPreferences);
        if (equivalent != null) {
            TextView loot = text(equivalent.format(), 12, BLUE, true);
            loot.setMaxLines(2);
            spend.addView(loot);
            spend.addView(text("趣味折算 · 金额仍以人民币为准", 10, MUTED, false));
        } else {
            spend.addView(text("CNY  /  PERSONAL ACCOUNTS", 9, MUTED, false));
        }
        boolean compact = getResources().getConfiguration().screenWidthDp < 380
                || getResources().getConfiguration().fontScale > 1.15f;
        headline.addView(spend, new LinearLayout.LayoutParams(0, -2, 1));
        headline.addView(TacticalVisuals.operator(this, uiPreferences.operatorStyle()),
                new LinearLayout.LayoutParams(dp(compact ? 82 : 105), dp(compact ? 108 : 136)));
        summary.addView(headline);
        spacer(summary, 14);
        rule(summary);
        spacer(summary, 15);
        LinearLayout values = horizontal();
        darkMetric(values, "收入", totals.incomeFen);
        darkMetric(values, "退款", totals.refundFen);
        darkMetric(values, "结余", totals.incomeFen + totals.refundFen - totals.expenseFen);
        summary.addView(values);
        spacer(summary, 12);
        long budget = uiPreferences.monthlyBudget(selectedYear, selectedMonth);
        TextView budgetLabel = text(budget > 0 ? "预算剩余  ¥ " + money(budget - totals.expenseFen) + "   ›" : "设置本月预算   ›", 12, MUTED, false);
        budgetLabel.setGravity(Gravity.CENTER_VERTICAL);
        budgetLabel.setMinHeight(dp(40));
        budgetLabel.setOnClickListener(v -> editBudget());
        summary.addView(budgetLabel);
        if (budget > 0) summary.addView(LedgerUi.categoryBar(this, totals.expenseFen, budget), new LinearLayout.LayoutParams(-1, dp(5)));
        content.addView(summary);
        spacer(content, 16);
        LinearLayout shortcut = horizontal();
        shortcut.setGravity(Gravity.CENTER_VERTICAL);
        shortcut.setPadding(dp(12), dp(7), dp(8), dp(7));
        shortcut.setBackground(feedback(SURFACE, 6));
        shortcut.addView(LedgerUi.icon(this, "inbox", BLUE, Color.TRANSPARENT), new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout copy = vertical();
        copy.setPadding(dp(10), 0, 0, 0);
        copy.addView(text(pending > 0 ? pending + " 笔记录待核对" : "自动记账", 13, INK, true));
        copy.addView(text(pending > 0 ? "核对金额、日期与重复关系" : captureStatus(), 11, MUTED, false));
        shortcut.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        shortcut.addView(text("›", 24, BLUE, false));
        shortcut.setOnClickListener(v -> { if (pending > 0) { reviewOpen = true; render(); } else openRecognition(); });
        content.addView(shortcut);
        spacer(content, 25);
        sectionHeading("账目明细", entries.size() + " 笔", this::render);
        addEntryGroups(content, entries, true);
        spacer(content, 8);
        content.addView(text("转账 ¥ " + money(totals.transferFen) + " · 单独统计，不计入消费", 11, MUTED, false));
    }
    private void darkMetric(LinearLayout row, String label, long amount) {
        LinearLayout column = vertical();
        column.addView(text(label, 11, MUTED, false));
        spacer(column, 6);
        TextView value = text(money(amount), 16, INK, true);
        value.setTypeface(Typeface.MONOSPACE);
        value.setMaxLines(1);
        value.setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        column.addView(value);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void renderReview(List<LedgerDb.Entry> entries, int pending) {
        content.addView(text(pending + " 笔等待核对", 22, INK, true));
        paragraph(content, "按采集时间从新到旧排列。未核对记录不计入统计。日期未知时，请对照付款账单确认。", 12);
        spacer(content, 16);
        addEntryGroups(content, entries, false);
        if (entries.size() >= 500) paragraph(content, "当前最多显示最近 500 笔待核对；处理后刷新可查看更早记录。", 12);
    }
    private void renderCalendar(List<LedgerDb.Entry> entries, LedgerDb.Totals totals, List<LedgerDb.DayTotal> days) {
        monthNavigation();
        TextView monthly = text("支出 ¥ " + money(totals.expenseFen) + "    收入 ¥ " + money(totals.incomeFen), 12, MUTED, false);
        monthly.setGravity(Gravity.CENTER);
        content.addView(monthly);
        spacer(content, 22);
        LinearLayout calendar = vertical();
        calendar.setPadding(dp(8), dp(12), dp(8), dp(12));
        calendar.setBackground(shape(SURFACE, 6));
        LinearLayout weekdays = horizontal();
        for (String day : new String[]{"一", "二", "三", "四", "五", "六", "日"}) {
            TextView label = text(day, 11, MUTED, false); label.setGravity(Gravity.CENTER);
            weekdays.addView(label, new LinearLayout.LayoutParams(0, dp(36), 1));
        }
        calendar.addView(weekdays);
        java.time.LocalDate first = java.time.LocalDate.of(selectedYear, selectedMonth, 1);
        int offset = first.getDayOfWeek().getValue() - 1, length = first.lengthOfMonth();
        for (int cell = 0; cell < ((offset + length + 6) / 7) * 7; cell += 7) {
            LinearLayout week = horizontal();
            for (int col = 0; col < 7; col++) {
                final int day = cell + col - offset + 1;
                LinearLayout tile = vertical(); tile.setGravity(Gravity.CENTER);
                if (day >= 1 && day <= length) {
                    LedgerDb.DayTotal value = days.get(day - 1);
                    boolean selected = day == selectedDay;
                    tile.setBackground(feedback(selected ? BLUE : Color.TRANSPARENT, 4));
                    TextView dayNumber = text(Integer.toString(day), 16, selected ? PAPER : INK, selected);
                    dayNumber.setGravity(Gravity.CENTER);
                    tile.addView(dayNumber);
                    spacer(tile, 5);
                    String amount = value.expenseFen > 0 ? "−" + shortMoney(value.expenseFen) : value.incomeFen > 0 ? "+" + shortMoney(value.incomeFen) : "·";
                    TextView detail = text(amount, 9, selected ? PAPER : value.expenseFen > 0 ? EXPENSE_COLOR : MUTED, false);
                    detail.setMaxLines(1);
                    detail.setGravity(Gravity.CENTER);
                    tile.addView(detail);
                    tile.setContentDescription(selectedMonth + "月" + day + "日，支出" + money(value.expenseFen) + "元，收入" + money(value.incomeFen) + "元");
                    tile.setOnClickListener(v -> { selectedDay = day; render(); });
                }
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, dp(60), 1);
                cellParams.setMargins(dp(1), dp(2), dp(1), dp(2));
                week.addView(tile, cellParams);
            }
            calendar.addView(week);
        }
        content.addView(calendar);
        spacer(content, 24);
        LedgerDb.DayTotal selected = days.get(selectedDay - 1);
        sectionHeading(selectedMonth + " 月 " + selectedDay + " 日", "支出 " + money(selected.expenseFen), null);
        List<LedgerDb.Entry> filtered = new ArrayList<>();
        for (LedgerDb.Entry e : entries) {
            Calendar timestamp = Calendar.getInstance(); timestamp.setTimeInMillis(e.createdAt);
            if (timestamp.get(Calendar.DAY_OF_MONTH) == selectedDay) filtered.add(e);
        }
        addEntryGroups(content, filtered, false);
    }
    private void renderAnalysis(LedgerDb.Totals totals, List<LedgerDb.DayTotal> days, List<LedgerDb.CategoryTotal> categories) {
        monthNavigation();
        TextView amount = text("¥ " + money(chartIncome ? totals.incomeFen : totals.expenseFen), 34, INK, true);
        amount.setMaxLines(1); amount.setAutoSizeTextTypeUniformWithConfiguration(18, 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        content.addView(text(chartIncome ? "本月收入" : "本月支出", 12, MUTED, false));
        content.addView(amount, new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout toggles = horizontal();
        for (int i = 0; i < 2; i++) {
            final boolean income = i == 1;
            Button toggle = button(income ? "收入" : "支出", chartIncome == income);
            toggle.setOnClickListener(v -> { chartIncome = income; render(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(0, 0, dp(8), 0);
            toggles.addView(toggle, p);
        }
        content.addView(toggles);
        spacer(content, 24);
        sectionHeading("每日走势", "已确认账目", null);
        content.addView(LedgerUi.chart(this, days, chartIncome), new LinearLayout.LayoutParams(-1, dp(210)));
        spacer(content, 26);
        sectionHeading("分类分布", chartIncome ? "收入" : "支出", null);
        long sum = chartIncome ? totals.incomeFen : totals.expenseFen;
        int rows = 0;
        for (LedgerDb.CategoryTotal value : categories) {
            long fen = chartIncome ? value.incomeFen : value.expenseFen;
            if (fen <= 0) continue;
            rows++;
            LinearLayout line = horizontal(); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(0, dp(14), 0, dp(8));
            line.addView(LedgerUi.icon(this, value.name, BLUE, TINT), new LinearLayout.LayoutParams(dp(36), dp(36)));
            LinearLayout label = vertical(); label.setPadding(dp(10), 0, 0, 0);
            label.addView(text(value.name, 14, INK, true));
            label.addView(text(sum > 0 ? String.format(Locale.CHINA, "%.1f%%", 100.0 * fen / sum) : "0%", 10, MUTED, false));
            line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text("¥ " + money(fen), 15, INK, true));
            content.addView(line);
            content.addView(LedgerUi.categoryBar(this, fen, sum), new LinearLayout.LayoutParams(-1, dp(5)));
        }
        if (rows == 0) emptyState(content, "本月暂无分类统计", "确认或手动记录账目后，这里会显示真实分布。", "记一笔", () -> editEntry(null));
        spacer(content, 22);
        paragraph(content, "退款 ¥ " + money(totals.refundFen) + " · 转账 ¥ " + money(totals.transferFen) + "，分别统计。", 11);
    }
    private void addEntryGroups(LinearLayout target, List<LedgerDb.Entry> entries, boolean groupDates) {
        if (entries.isEmpty()) {
            emptyState(target, "暂无账目记录", "记下第一笔，或在自动记账中检查权限。", "记一笔", () -> editEntry(null));
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("M 月 d 日  EEEE", Locale.CHINA);
        String previousDay = "";
        int count = 0;
        for (LedgerDb.Entry entry : entries) {
            if (++count > visibleLimit) break;
            String day = entry.needsTimeConfirmation ? "日期待核对" : format.format(new Date(entry.createdAt));
            if (groupDates && !previousDay.equals(day)) {
                spacer(target, 22);
                target.addView(text(day, 12, MUTED, true));
                spacer(target, 8);
                previousDay = day;
            }
            addEntry(target, entry);
        }
        if (entries.size() > visibleLimit) {
            action(target, "继续显示（剩余 " + (entries.size() - visibleLimit) + " 笔）", false, () -> { visibleLimit += 100; render(); });
        }
    }
    private void addEntry(LinearLayout target, LedgerDb.Entry entry) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(15), dp(12), dp(15));
        row.setBackground(feedback(SURFACE, 4));
        LootCatalog.Match equivalent = "expense".equals(entry.direction) ? LootCatalog.match(entry.amountFen, uiPreferences) : null;
        View symbol = equivalent == null ? LedgerUi.icon(this, entry.category, BLUE, TINT)
                : TacticalVisuals.loot(this, equivalent.item.kind);
        row.addView(symbol, new LinearLayout.LayoutParams(dp(42), dp(42)));
        String merchant = entry.merchant == null || entry.merchant.trim().isEmpty() ? entry.sourceLabel : entry.merchant;
        LinearLayout description = vertical(); description.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(merchant, 14, INK, true); name.setMaxLines(2); description.addView(name);
        spacer(description, 3);
        description.addView(text(entry.category + " · " + entry.sourceLabel + (entry.needsTimeConfirmation ? "" : " · "
                + (reviewOpen ? fullTimeFormat : timeFormat).format(new Date(entry.createdAt))), 10, MUTED, false));
        if (reviewOpen) {
            description.addView(text(entry.capturedAt > 0 ? "采集于 " + fullTimeFormat.format(new Date(entry.capturedAt)) : "采集时间未知", 10, MUTED, false));
        }
        if (entry.needsTimeConfirmation || "pending".equals(entry.status)) {
            description.addView(text(entry.needsTimeConfirmation ? "待核对日期" : "待核对", 10, CAUTION, false));
        }
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        String sign = "expense".equals(entry.direction) ? "−" : "transfer".equals(entry.direction) ? "↔" : "+";
        TextView amount = text(sign + money(entry.amountFen), 17, "expense".equals(entry.direction) ? EXPENSE_COLOR : INK, true);
        amount.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        amount.setMaxLines(1);
        amount.setGravity(Gravity.END);
        amount.setAutoSizeTextTypeUniformWithConfiguration(10, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout amounts = vertical();
        amounts.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        amounts.addView(amount, new LinearLayout.LayoutParams(-1, dp(30)));
        if (equivalent != null) {
            TextView comparison = text(equivalent.format(), 10, MUTED, false);
            comparison.setGravity(Gravity.END);
            comparison.setMaxLines(2);
            comparison.setEllipsize(TextUtils.TruncateAt.END);
            comparison.setContentDescription("趣味折算，" + equivalent.format());
            amounts.addView(comparison, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(amounts, new LinearLayout.LayoutParams(dp(126), -2));
        row.setOnClickListener(v -> showEntry(entry, merchant));
        target.addView(row); spacer(target, 2);
    }
    private void sectionHeading(String title, String trailing, Runnable action) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = text(trailing, 11, MUTED, false); detail.setGravity(Gravity.CENTER_VERTICAL); detail.setMinHeight(dp(40));
        if (action != null) detail.setOnClickListener(v -> action.run());
        row.addView(detail); content.addView(row);
    }
    private void emptyState(LinearLayout target, String title, String message, String actionLabel, Runnable action) {
        LinearLayout empty = vertical(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(14), dp(36), dp(14), dp(28));
        empty.addView(TacticalVisuals.loot(this), new LinearLayout.LayoutParams(dp(82), dp(72)));
        spacer(empty, 20); empty.addView(text(title, 18, INK, true));
        TextView caption = text(message, 12, MUTED, false); caption.setGravity(Gravity.CENTER); caption.setPadding(0, dp(8), 0, dp(18)); empty.addView(caption);
        Button add = button(actionLabel, false); add.setTextColor(INK); add.setBackground(feedback(TINT, 10)); add.setOnClickListener(v -> action.run()); empty.addView(add, new LinearLayout.LayoutParams(dp(150), dp(48)));
        target.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }
    private String shortMoney(long fen) {
        if (fen >= 1_000_000) return String.format(Locale.CHINA, "%.1f万", fen / 1_000_000.0);
        return BigDecimal.valueOf(fen, 2).stripTrailingZeros().toPlainString();
    }
    private void showEntry(LedgerDb.Entry entry, String merchant) {
        String date = entry.needsTimeConfirmation ? "尚未核对（不能以采集时间代替）" : fullTimeFormat.format(new Date(entry.createdAt));
        String message = directionName(entry.direction) + " · " + entry.category + "\n交易日期：" + date
                + "\n来源：" + entry.sourceLabel + "\n采集时间：" + (entry.capturedAt > 0 ? fullTimeFormat.format(new Date(entry.capturedAt)) : "旧版本未记录")
                + "\n核对提示：" + entry.reason;
        LootCatalog.Match equivalent = "expense".equals(entry.direction) ? LootCatalog.match(entry.amountFen, uiPreferences) : null;
        if (equivalent != null) message += "\n\n趣味折算：" + equivalent.format()
                + "\n当前设定：¥" + money(equivalent.referenceFen) + " / 1" + equivalent.item.unit + equivalent.item.name
                + "\n仅为个人参考，不是游戏交易价格。";
        if (entry.note != null && !entry.note.isEmpty()) message += "\n备注：" + entry.note;
        AlertDialog.Builder detail = new AlertDialog.Builder(this).setTitle(merchant + "  ¥ " + money(entry.amountFen)).setMessage(message);
        if (entry.needsTimeConfirmation) {
            detail.setPositiveButton("核对日期并编辑", (d, w) -> editEntry(entry)).setNeutralButton("返回", null);
        } else if ("pending".equals(entry.status)) {
            detail.setPositiveButton("确认入账", (d, w) -> mutate(() -> db.confirm(entry.id)))
                    .setNeutralButton("编辑", (d, w) -> editEntry(entry));
        } else {
            detail.setPositiveButton("编辑", (d, w) -> editEntry(entry)).setNeutralButton("返回", null);
        }
        detail.setNegativeButton("删除", (d, w) -> confirmDelete(entry.id)).show();
    }
    private void renderProfile() {
        LinearLayout identity = horizontal(); identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setPadding(dp(12), dp(12), dp(12), dp(12));
        identity.setBackground(TacticalVisuals.panel(this, false));
        identity.addView(TacticalVisuals.operator(this, uiPreferences.operatorStyle()), new LinearLayout.LayoutParams(dp(80), dp(104)));
        LinearLayout label = vertical(); label.setPadding(dp(16), 0, 0, 0);
        label.addView(text("粥粥记账", 21, INK, true));
        label.addView(text("无需账号 · 数据保存在本机", 11, MUTED, false));
        identity.addView(label); content.addView(identity);
        spacer(content, 20);
        LinearLayout tools = horizontal();
        profileShortcut(tools, "edit", "自动记账", this::openRecognition);
        profileShortcut(tools, "chart", "月度预算", this::editBudget);
        profileShortcut(tools, "other", "分类管理", this::manageCategories);
        content.addView(tools);
        spacer(content, 22);
        sectionHeading("战利品等价", "趣味展示", null);
        settingRow("消费金额换成藏品", "真实人民币旁显示近似物品数量", uiPreferences.lootEnabled() ? "已开启" : "已关闭",
                () -> { uiPreferences.setLootEnabled(!uiPreferences.lootEnabled()); render(); });
        settingRow("物品对照与参考金额", LootCatalog.items().size() + " 项参考藏品 · " + LootCatalog.configuredCount(uiPreferences) + " 项已设金额", "设置", this::showLootReferences);
        String[] outfits = {"毛茸侦察员", "麦晓雯", "猫耳伙伴"};
        settingRow("Q 版伙伴", outfits[uiPreferences.operatorStyle()], "切换", this::chooseOperator);
        spacer(content, 18);
        sectionHeading("自动记账", "", null);
        settingRow("识别状态", captureStatus(), "查看", this::openRecognition);
        settingRow("待核对记录", "核对金额、交易日期与重复关系", "打开", () -> { reviewOpen = true; render(); });
        spacer(content, 18);
        sectionHeading("数据管理", "仅在本机", null);
        settingRow("导出账本", "导出全部可见记录为 CSV", "导出", this::exportCsv);
        settingRow("隐私说明", "本机处理、权限范围与数据保留", "阅读", this::showPrivacy);
        spacer(content, 18);
        settingRow("粥粥记账 0.4.1", "MIT 开源 · 实验版本", "许可", this::showLicense);
        paragraph(content, "CSV 未加密，当前无法导入恢复。卸载或清除数据会丢失账本。", 11);
    }
    private void profileShortcut(LinearLayout row, String icon, String name, Runnable action) {
        LinearLayout item = vertical(); item.setGravity(Gravity.CENTER); item.setPadding(0, dp(16), 0, dp(14));
        item.setBackground(feedback(SURFACE, 6));
        item.addView(LedgerUi.icon(this, icon, BLUE, TINT), new LinearLayout.LayoutParams(dp(42), dp(42)));
        spacer(item, 10); item.addView(text(name, 12, INK, true)); item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(item, params);
    }
    private void openRecognition() { recognitionOpen = true; reviewOpen = false; render(); }
    private void chooseOperator() {
        LinearLayout options = vertical();
        options.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(options);
        AlertDialog chooser = new AlertDialog.Builder(this).setTitle("选择 Q 版伙伴").setView(scroll)
                .setNegativeButton("返回", null).create();
        String[] names = {"毛茸侦察员", "麦晓雯", "猫耳伙伴"};
        for (int i = 0; i < names.length; i++) {
            final int variant = i;
            boolean selected = variant == uiPreferences.operatorStyle();
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(12), dp(8));
            row.setMinimumHeight(dp(148));
            row.setBackground(feedback(selected ? SURFACE : Color.TRANSPARENT, 6));
            row.addView(TacticalVisuals.operator(this, variant), new LinearLayout.LayoutParams(dp(104), dp(132)));
            LinearLayout caption = vertical();
            caption.setPadding(dp(16), 0, 0, 0);
            caption.addView(text(names[i], 16, INK, true));
            spacer(caption, 8);
            caption.addView(text(selected ? "已选中" : "点按选择", 12, selected ? BLUE : MUTED, false));
            row.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
            row.setSelected(selected);
            row.setFocusable(true);
            row.setContentDescription(names[i] + (selected ? "，已选中" : "，点按选择"));
            row.setOnClickListener(v -> { uiPreferences.setOperatorStyle(variant); chooser.dismiss(); render(); });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, 0, 0, dp(6));
            options.addView(row, rowParams);
        }
        chooser.show();
    }
    private void showLootReferences() {
        LinearLayout list = vertical();
        list.setPadding(dp(20), dp(8), dp(20), dp(16));
        paragraph(list, "仅用于消费金额的趣味比喻。参考金额由你逐件设定，互不联动，不是人民币与游戏币的汇率；修改不影响账本金额与统计。", 12);
        paragraph(list, "收录 " + LootCatalog.items().size() + " 项资料快照（" + LootCatalog.SOURCE_DATE
                + "），不是当前完整清单。默认按怀表 ¥20 的趣味比例设置；没有历史估值的物品需手动设金额。", 11);
        spacer(list, 14);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog referenceList = new AlertDialog.Builder(this).setTitle("战利品参考 · " + LootCatalog.items().size() + " 项").setView(scroll)
                .setPositiveButton("完成", null).create();
        for (LootCatalog.Item item : LootCatalog.items()) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.addView(TacticalVisuals.loot(this, item.kind), new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout copy = vertical();
            copy.setPadding(dp(12), 0, dp(8), 0);
            copy.addView(text(item.name, 14, INK, true));
            long reference = uiPreferences.lootReferenceFen(item.id, item.defaultFen);
            copy.addView(text(reference > 0 ? "¥ " + money(reference) + " / 1" + item.unit : "未设参考金额 · 暂不参与折算", 12, MUTED, false));
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            Button edit = button("调整", false);
            edit.setContentDescription("调整" + item.name + "的趣味参考金额");
            row.addView(edit, new LinearLayout.LayoutParams(dp(56), dp(48)));
            list.addView(row);
            rule(list);
            edit.setOnClickListener(v -> { referenceList.dismiss(); editLootReference(item); });
        }
        referenceList.show();
    }
    private void editLootReference(LootCatalog.Item item) {
        LinearLayout form = vertical();
        form.setPadding(dp(24), dp(12), dp(24), 0);
        form.addView(text("1" + item.unit + item.name + " 对应多少人民币？", 14, INK, false));
        EditText value = new EditText(this);
        value.setHint("大于 0 的参考金额（元）");
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        long current = uiPreferences.lootReferenceFen(item.id, item.defaultFen);
        if (current > 0) value.setText(money(current));
        value.selectAll();
        form.addView(value);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle("调整趣味参考金额").setView(form)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        editor.setOnShowListener(dialog -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String number = value.getText().toString().trim();
                if (!number.matches("[0-9]{1,9}(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
                uiPreferences.setLootReferenceFen(item.id, new BigDecimal(number).movePointRight(2).longValueExact());
                editor.dismiss(); render();
                toast("已更新" + item.name + "的趣味参考金额");
                showLootReferences();
            } catch (RuntimeException invalid) { value.setError("请输入大于 0 的有效金额，最多两位小数"); }
        }));
        editor.show();
    }
    private void editBudget() {
        LinearLayout form = vertical(); form.setPadding(dp(24), dp(12), dp(24), 0);
        form.addView(text(selectedYear + " 年 " + selectedMonth + " 月支出预算", 13, MUTED, false));
        EditText value = new EditText(this); value.setHint("人民币元，留空表示不设置");
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        long existing = uiPreferences.monthlyBudget(selectedYear, selectedMonth);
        if (existing > 0) value.setText(money(existing));
        form.addView(value);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("月度预算").setView(form)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String number = value.getText().toString().trim();
                if (!number.isEmpty() && !number.matches("[0-9]{1,9}(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
                long fen = number.isEmpty() ? 0 : new BigDecimal(number).movePointRight(2).longValueExact();
                uiPreferences.setMonthlyBudget(selectedYear, selectedMonth, fen);
                dialog.dismiss(); render();
            } catch (RuntimeException invalid) { value.setError("请输入有效金额，最多两位小数"); }
        }));
        dialog.show();
    }
    private void manageCategories() {
        List<String> names = uiPreferences.categories();
        new AlertDialog.Builder(this).setTitle("记账分类 · " + names.size() + " 项")
                .setItems(names.toArray(new String[0]), (d, index) -> toast("记账时可选择“" + names.get(index) + "”"))
                .setPositiveButton("新增分类", (d, w) -> {
                    EditText name = new EditText(this); name.setHint("输入分类名称");
                    name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
                    AlertDialog editor = new AlertDialog.Builder(this).setTitle("新增分类").setView(name)
                            .setPositiveButton("添加", null).setNegativeButton("取消", null).create();
                    editor.setOnShowListener(dialog -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        try { uiPreferences.addCategory(name.getText().toString()); editor.dismiss(); manageCategories(); }
                        catch (RuntimeException invalid) { name.setError(invalid.getMessage()); }
                    }));
                    editor.show();
                }).setNegativeButton("完成", null).show();
    }
    private void searchEntries() {
        EditText query = new EditText(this); query.setHint("商户、分类、备注或金额");
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        new AlertDialog.Builder(this).setTitle("搜索账目").setView(query).setNegativeButton("取消", null)
                .setPositiveButton("搜索", (d, w) -> {
                    String term = query.getText().toString().trim();
                    if (term.isEmpty()) { toast("请输入关键词"); return; }
                    worker.execute(() -> {
                        try {
                            List<LedgerDb.Entry> results = db.search(term);
                            runOnUiThread(() -> {
                                if (isDestroyed()) return;
                                LinearLayout list = vertical(); list.setPadding(dp(12), dp(8), dp(12), dp(16));
                                if (results.isEmpty()) list.addView(text("没有找到匹配的账目", 14, MUTED, false));
                                if (results.size() >= 500) list.addView(text("显示最近 500 笔匹配账目", 11, MUTED, false));
                                ScrollView scroll = new ScrollView(this); scroll.addView(list);
                                AlertDialog found = new AlertDialog.Builder(this).setTitle("搜索结果 · " + results.size() + " 笔").setView(scroll).setPositiveButton("关闭", null).create();
                                for (LedgerDb.Entry e : results) {
                                    addEntry(list, e);
                                    View row = list.getChildAt(list.getChildCount() - 2);
                                    row.setOnClickListener(v -> {
                                        found.dismiss();
                                        showEntry(e, e.merchant == null || e.merchant.trim().isEmpty() ? e.sourceLabel : e.merchant);
                                    });
                                }
                                found.show();
                            });
                        } catch (Exception error) { showFailure("搜索未完成，请稍后重试"); }
                    });
                }).show();
    }
    private void renderSettings() {
        paragraph(content, "授权后尝试识别支付通知和付款页面。请与实际交易核对。", 13);
        sectionLabel("01", "识别与权限");
        settingRow("自动识别", captureStatus(), settings.isCaptureEnabled() ? "暂停" : "开启", this::toggleCapture);
        settingRow("通知使用权", notificationAccess() ? "系统已授权；总开关开启后才处理选定来源。" : "尚未授权。读取选定来源的明确交易通知。", "设置",
                () -> openSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        String pageStatus = !settings.isAccessibilityCaptureEnabled() ? "页面识别已关闭，可单独选择开启。"
                : accessibilityAccess() ? "系统已授权；付款页候选先进入待核对。" : "已同意页面识别，尚待系统授权。";
        settingRow("付款页面识别", pageStatus, "设置", this::configurePages);
        paragraph(content, "受保护页面、没有金额的通知、应用更新和系统后台限制都可能导致漏记。购物订单不等于付款。", 12);
        sectionLabel("02", "识别来源");
        settingRow("来源白名单", "当前选择 " + settings.getEnabledPackages().size() + " 个应用；默认只选微信和支付宝。", "选择", this::chooseSources);
        paragraph(content, "付款页规则仅用于微信、支付宝、云闪付及四个电商平台。银行和新增来源只尝试通知规则。电商、银行、付款页及关系不明的转账先待核对，尚未完成实机适配。", 12);
        settingRow("后台运行", "识别依赖系统授予的服务权限与后台运行条件。", "查看",
                () -> openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        long issues = settings.getCaptureIssueCount();
        if (issues > 0) {
            String issue = switch (settings.getLastCaptureIssueCode()) {
                case "queue_full" -> "采集队列已满"; case "write_failed" -> "本地写入未完成"; default -> "来源暂不可读";
            };
            settingRow("有 " + issues + " 次采集未完成", issue + " · " + fullTimeFormat.format(new Date(settings.getLastCaptureIssueAt()))
                    + "。请对照实际付款补记；此提示只保留次数、时间和原因类型。", "清除", () -> { settings.clearCaptureIssues(); render(); });
        }
        sectionLabel("03", "识别诊断");
        renderDiagnostic("通知", settings.getNotificationDiagnostic());
        renderDiagnostic("付款页面", settings.getAccessibilityDiagnostic());
        paragraph(content, "以上为最后记录的阶段，不代表实时连接状态。诊断仅保留原因、来源标签、时间、次数和页面节点计数，不保存通知或页面正文。", 11);
        action(content, "刷新诊断", false, this::render);
        action(content, "检查页面服务", false, () -> new AlertDialog.Builder(this)
                .setTitle("页面服务检查")
                .setMessage(PaymentAccessibilityService.inspectOwnWindow())
                .setPositiveButton("知道了", null).show());
        action(content, "清除诊断记录", false, () -> { settings.clearCaptureDiagnostics(); render(); });
    }
    private void renderDiagnostic(String title, AppSettings.CaptureDiagnostic info) {
        spacer(content, 16);
        content.addView(text(title + " · " + AppSettings.describeDiagnosticStage(info.stage), 15, INK, true));
        paragraph(content, info.observedAt > 0 ? "最后收到事件：" + fullTimeFormat.format(new Date(info.observedAt))
                + " · " + info.sourceLabel + " · 累计 " + info.eventCount + " 次" : "尚未收到已选择来源的事件", 11);
        paragraph(content, AppSettings.describeDiagnosticReason(info.reason), 12);
        if (info.structureObservedAt > 0) paragraph(content, "页面结构：" + info.nodeCount + " 个节点，"
                + info.visibleNodeCount + " 个标记可见，" + info.textNodeCount + " 个提供文字"
                + "；根节点" + (info.rootVisible ? "标记可见" : "未标记可见")
                + "。采样于 " + fullTimeFormat.format(new Date(info.structureObservedAt)), 11);
        if (info.readModeAt > 0) paragraph(content, "微信页面兼容采样（" + fullTimeFormat.format(new Date(info.readModeAt)) + "）："
                + "包含普通容器" + (info.includeContainersRequested ? "已请求" : "未请求")
                + " / " + (info.includeContainersActive ? "已启用" : "未启用")
                + "；根子节点 " + (info.rootChildCount < 0 ? "未知" : info.rootChildCount)
                + (info.rootChildCount < 0 ? "" : "；根重要性 " + (info.rootImportant ? "是" : "否")
                    + "；事件与根窗口" + (info.eventWindowMatchesRoot ? "相同" : "不同"))
                + "。该采样与其他诊断按各自时间判断。", 11);
        if (info.notificationMetadataAt > 0) paragraph(content, "微信通知模板采样（"
                + fullTimeFormat.format(new Date(info.notificationMetadataAt)) + "）："
                + (info.titleSupported ? "标题符合付款模板" : "标题不符合付款模板")
                + "；消息分类 " + (info.messageCategory ? "是" : "否")
                + "；样式 " + info.notificationTemplateKind
                + "；消息数组 " + (info.hasMessages ? "有" : "无")
                + "；历史消息 " + (info.hasHistoric ? "有" : "无")
                + "；可回复 " + (info.hasRemoteInput ? "是" : "否")
                + "。仅记录结构，标题匹配不能认证付款来源。", 11);
        if (info.templateAt > 0) {
            paragraph(content, "最近规则详情（" + fullTimeFormat.format(new Date(info.templateAt)) + "）："
                    + (info.merchantRecognized ? "已提取商户" : "商户待补充")
                    + " · " + (info.transactionIdFound ? "有明确交易标识" : "未提取交易标识")
                    + (info.parsedDirection.isEmpty() ? "" : " · " + directionName(info.parsedDirection)), 11);
            if (!info.templateShape.isEmpty()) paragraph(content, "模板类型：" + info.templateShape, 10);
        }
        if (info.lastResultAt > 0) paragraph(content, "最后处理结果：" + AppSettings.describeDiagnosticStage(info.lastResultStage)
                + " · " + AppSettings.describeDiagnosticReason(info.lastResultReason), 12);
        if (info.lastMatchAt > 0) paragraph(content, "最近账目处理（" + fullTimeFormat.format(new Date(info.lastMatchAt)) + "）："
                + AppSettings.describeDiagnosticReason(info.lastMatchReason)
                + "。该记录可能早于最后页面事件，请以账本内容为准。", 11);
    }
    private String captureStatus() {
        if (!settings.isCaptureEnabled()) return "自动识别已暂停";
        boolean notifications = notificationAccess();
        boolean pages = settings.isAccessibilityCaptureEnabled() && accessibilityAccess();
        if (!notifications && !pages) return "已开启总开关，仍需系统授权";
        return "自动识别已开启 · " + (notifications && pages ? "通知与付款页" : notifications ? "通知" : "付款页");
    }
    private void toggleCapture() {
        if (settings.isCaptureEnabled()) { settings.setCaptureEnabled(false); render(); return; }
        new AlertDialog.Builder(this).setTitle("开启本地识别")
                .setMessage("开启后，仅处理你选定来源的交易通知。付款页面识别还需单独同意并授权。\n\n通知和页面文字可能包含财务及个人信息。原文仅在本机内存中短暂解析，不保存整段原文、不上传；账本保留金额、商户、日期和去重摘要等结构化信息。\n\n明确支付通知尝试自动入账，其余候选先待核对。可能漏记或重复，请与实际交易核对。")
                .setPositiveButton("同意并开启", (d, w) -> { settings.setCaptureEnabled(true); render(); }).setNegativeButton("取消", null).show();
    }
    private void configurePages() {
        if (settings.isAccessibilityCaptureEnabled()) {
            new AlertDialog.Builder(this).setTitle("付款页面识别")
                    .setItems(new String[]{"打开系统无障碍设置", "关闭页面识别"}, (d, w) -> {
                        if (w == 0) openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        else { settings.setAccessibilityCaptureEnabled(false); render(); }
                    }).setNegativeButton("返回", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("使用付款页面文字")
                .setMessage("需在系统无障碍设置中手动开启“粥粥记账 · 付款页面识别”。开启后，仅从你选定来源的可访问页面提取付款状态、金额和日期等文字。\n\n不自动点击、输入、付款或截屏；不上传，不保存整段页面原文。遇到密码或可编辑内容、过大页面时放弃解析。可随时在应用内或系统中关闭。\n\n页面候选会进入待核对；未能识别交易日期时，必须由你确认日期和时间。侧载应用如遇授权限制，可在系统应用详情查看“允许受限制的设置”。")
                .setPositiveButton("同意，去授权", (d, w) -> {
                    settings.setAccessibilityCaptureEnabled(true); openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }).setNegativeButton("取消", null).show();
    }
    private void chooseSources() {
        LinearLayout list = vertical();
        list.setPadding(dp(24), dp(8), dp(24), dp(12));
        list.addView(text("只选择你希望用于记账的应用。电商受保护页面可能完全无法读取。", 12, MUTED, false));
        Set<String> known = new HashSet<>(Arrays.asList(AppSettings.KNOWN_PACKAGES));
        for (int i = 0; i < AppSettings.KNOWN_PACKAGES.length; i++) sourceCheckbox(list, AppSettings.KNOWN_LABELS[i], AppSettings.KNOWN_PACKAGES[i]);
        for (String name : settings.getEnabledPackages()) if (!known.contains(name)) sourceCheckbox(list, AppSettings.getPackageLabel(name) + "\n" + name, name);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        new AlertDialog.Builder(this).setTitle("识别来源").setView(scroll).setPositiveButton("完成", (d, w) -> render())
                .setNeutralButton("添加应用", (d, w) -> chooseInstalledApp()).show();
    }
    private void sourceCheckbox(LinearLayout parent, String label, String packageName) {
        CheckBox box = new CheckBox(this);
        box.setText(label); box.setTextSize(14); box.setTextColor(INK); box.setMinHeight(dp(48));
        box.setChecked(settings.isPackageEnabled(packageName));
        box.setOnCheckedChangeListener((b, checked) -> settings.setPackageEnabled(packageName, checked));
        parent.addView(box);
    }
    private void showPrivacy() {
        new AlertDialog.Builder(this).setTitle("你的数据，留在本机")
                .setMessage("两个采集开关默认关闭，系统授权也需你手动授予。仅处理你选择的来源。\n\n通知与页面原文只在本机内存短暂解析，不保存整段原文；私有 SQLite 保存金额、类型、商户、日期、来源、备注及去重摘要。数据库使用系统应用隔离，没有额外的数据库加密。\n\n本应用没有网络权限，没有账号、统计 SDK 或云同步。已排除系统云备份和设备迁移；厂商迁移行为仍由系统决定。\n\n删除会清空记录明细，但保留有限去重摘要以减少再次出现，不能保证物理介质上的安全擦除。去重缓存最多保留 90 天及 20,000 项，账本记录不随缓存过期删除。\n\n导出 CSV 含财务信息，文件未加密，尚不能导入恢复。卸载或清除数据会丢失账本。采集异常仅保存次数、时间与原因类型。完整隐私说明随源码提供。")
                .setPositiveButton("知道了", null).show();
    }
    private void showLicense() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getResources().openRawResource(R.raw.project_license), StandardCharsets.UTF_8))) {
            StringBuilder license = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) license.append(line).append('\n');
            new AlertDialog.Builder(this).setTitle("开源许可 · MIT").setMessage(license.toString()).setPositiveButton("关闭", null).show();
        } catch (Exception error) { toast("许可文本暂时无法读取，请参阅源码中的 LICENSE"); }
    }
    private void editEntry(LedgerDb.Entry existing) { editEntry(existing, null); }
    private void editEntry(LedgerDb.Entry existing, Bundle draft) {
        if (activeSave != null) { toast("账目正在保存，请稍候"); return; }
        if (uncertainSaveDraft != null) { reviewUncertainSave(); return; }
        long initialTime = System.currentTimeMillis();
        if (selectedTab == 1 && existing == null) {
            Calendar value = Calendar.getInstance();
            value.set(Calendar.DAY_OF_MONTH, 1);
            value.set(Calendar.YEAR, selectedYear); value.set(Calendar.MONTH, selectedMonth - 1); value.set(Calendar.DAY_OF_MONTH, selectedDay);
            initialTime = value.getTimeInMillis();
        }
        editorDialog = new QuickEntryDialog(this, existing, draft, initialTime,
                (fen, direction, merchant, category, note, when, completion) -> {
                    if (activeSave != null) { completion.finish(false); return; }
                    SaveOperation operation = new SaveOperation(getApplicationContext(), editorDialog.snapshot(),
                            existing == null ? -1 : existing.id, fen, direction, merchant, category, note, when);
                    activeSave = operation;
                    saveCompletion = completion;
                    operation.attach(this);
                    try { operation.start(); }
                    catch (RuntimeException error) {
                        operation.detach(this);
                        activeSave = null;
                        saveCompletion = null;
                        throw error;
                    }
                });
        editorDialog.show();
    }
    private void restoreEntryDraft(Bundle draft) {
        LedgerDb.Entry existing = null;
        if (draft.getLong("entry_id", -1) > 0) {
            existing = new LedgerDb.Entry();
            existing.id = draft.getLong("entry_id");
            existing.createdAt = draft.getLong("transaction_at");
            existing.needsTimeConfirmation = draft.getBoolean("needs_time");
            existing.direction = draft.getString("direction", "expense");
        }
        editEntry(existing, draft);
    }
    private void finishSave(SaveOperation operation) {
        if (!resumed || isDestroyed() || activeSave != operation || !operation.finished) return;
        operation.detach(this);
        activeSave = null;
        dismissSavingDialog();
        QuickEntryDialog.Completion completion = saveCompletion;
        saveCompletion = null;
        if (completion != null && editorDialog != null && editorDialog.isShowing()) {
            completion.finish(operation.succeeded);
        } else if (!operation.succeeded) {
            restoreEntryDraft(new Bundle(operation.draft));
            toast("未能保存，草稿已恢复，请重试");
        }
        if (operation.succeeded) {
            Calendar chosen = Calendar.getInstance(); chosen.setTimeInMillis(operation.when);
            selectedYear = chosen.get(Calendar.YEAR); selectedMonth = chosen.get(Calendar.MONTH) + 1; selectedDay = chosen.get(Calendar.DAY_OF_MONTH);
            render();
        }
    }
    private void dismissSavingDialog() {
        if (savingDialog != null) { savingDialog.dismiss(); savingDialog = null; }
    }
    private void reviewUncertainSave() {
        if (uncertainSaveDraft == null) return;
        new AlertDialog.Builder(this).setTitle("先核对上次保存")
                .setMessage("应用上次退出时正在保存，结果无法确认。请先核对已有记录的金额、商户和日期，避免重复记账。\n\n确认没有这笔记录后，可以恢复草稿，再由你决定是否保存。")
                .setPositiveButton("确认未记账，恢复草稿", (dialog, which) -> {
                    if (activeSave != null) { toast("请先等待当前保存完成"); return; }
                    Bundle draft = new Bundle(uncertainSaveDraft);
                    uncertainSaveDraft = null; uncertainSaveId = null;
                    render();
                    restoreEntryDraft(draft);
                })
                .setNeutralButton("已找到记录，丢弃草稿", (dialog, which) -> {
                    uncertainSaveDraft = null; uncertainSaveId = null; render();
                })
                .setNegativeButton("继续核对", null).show();
    }
    /** A configuration change retains data and operation identity, never an Activity or Dialog. */
    private static final class RetainedSaveState {
        final SaveOperation operation;
        final Bundle editorDraft, uncertainDraft;
        final String uncertainId;
        RetainedSaveState(SaveOperation operation, Bundle editorDraft, String uncertainId, Bundle uncertainDraft) {
            this.operation = operation;
            this.editorDraft = editorDraft == null ? null : new Bundle(editorDraft);
            this.uncertainId = uncertainId;
            this.uncertainDraft = uncertainDraft == null ? null : new Bundle(uncertainDraft);
        }
    }
    private static final class SaveOperation implements Runnable {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        final String id = UUID.randomUUID().toString();
        final Bundle draft;
        final long entryId, amountFen, when;
        final String direction, merchant, category, note;
        private final Context application;
        private WeakReference<MainActivity> observer = new WeakReference<>(null);
        volatile boolean finished;
        boolean succeeded;
        SaveOperation(Context application, Bundle draft, long entryId, long amountFen, String direction,
                      String merchant, String category, String note, long when) {
            this.application = application;
            this.draft = new Bundle(draft);
            this.entryId = entryId; this.amountFen = amountFen; this.when = when;
            this.direction = direction; this.merchant = merchant; this.category = category; this.note = note;
        }
        void start() { new Thread(this, "ledger-manual-save").start(); }
        void attach(MainActivity activity) {
            observer = new WeakReference<>(activity);
            MAIN.post(this::deliver);
        }
        void detach(MainActivity activity) {
            if (observer.get() == activity) observer.clear();
        }
        @Override public void run() {
            LedgerDb ledger = null;
            try {
                ledger = new LedgerDb(application);
                if (entryId > 0) ledger.update(entryId, amountFen, direction, merchant, category, note, when);
                else ledger.insertManual(amountFen, direction, merchant, category, when, note);
                succeeded = true;
            } catch (Exception error) { succeeded = false; }
            finally {
                // A close failure must not turn an already committed write into a retryable failure.
                if (ledger != null) try { ledger.close(); } catch (Exception ignored) { }
                finished = true;
                MAIN.post(this::deliver);
            }
        }
        private void deliver() {
            MainActivity activity = observer.get();
            if (finished && activity != null) activity.finishSave(this);
        }
    }
    private void chooseInstalledApp() {
        Intent launchers = new Intent(Intent.ACTION_MAIN); launchers.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launchers, 0);
        List<String[]> apps = new ArrayList<>(); Set<String> packages = new HashSet<>();
        for (ResolveInfo item : resolved) {
            String name = item.activityInfo.packageName;
            if (!getPackageName().equals(name) && packages.add(name)) apps.add(new String[]{String.valueOf(item.loadLabel(getPackageManager())), name});
        }
        apps.sort(Comparator.comparing(a -> a[0]));
        if (apps.isEmpty()) { toast("没有找到可选应用"); return; }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
        new AlertDialog.Builder(this).setTitle("选择需要记账的应用").setItems(labels, (dialog, which) -> {
            String packageName = apps.get(which)[1];
            new AlertDialog.Builder(this).setTitle(apps.get(which)[0])
                    .setMessage("这是银行应用吗？银行通知按独立规则生成待核对候选。其他新应用需要新增规则才能识别，加入列表不会自动获得支持。")
                    .setPositiveButton("银行应用", (d, w) -> { settings.setBankPackage(packageName, true); settings.setPackageEnabled(packageName, true); render(); })
                    .setNeutralButton("其他付款应用", (d, w) -> { settings.setBankPackage(packageName, false); settings.setPackageEnabled(packageName, true); render(); })
                    .setNegativeButton("取消", null).show();
        }).setNegativeButton("取消", null).show();
    }
    private void exportCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv"); intent.putExtra(Intent.EXTRA_TITLE, "粥粥记账-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date()) + ".csv");
        try { startActivityForResult(intent, EXPORT_CSV); }
        catch (RuntimeException error) { toast("未找到文件保存入口，请检查系统文件管理器"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_CSV || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        worker.execute(() -> {
            try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                if (stream == null) throw new IllegalStateException("No destination stream");
                stream.write(db.exportCsv().getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> { if (!isDestroyed()) toast("账目已导出"); });
            } catch (Exception error) { showFailure("导出未完成，请检查所选保存位置"); }
        });
    }
    private boolean notificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName component = new ComponentName(this, LedgerNotificationService.class);
        for (String flattened : enabled.split(":")) if (component.equals(ComponentName.unflattenFromString(flattened))) return true;
        return false;
    }
    private boolean accessibilityAccess() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ResolveInfo info = service.getResolveInfo();
            if (info != null && info.serviceInfo != null && getPackageName().equals(info.serviceInfo.packageName)
                    && PaymentAccessibilityService.class.getName().equals(info.serviceInfo.name)) return true;
        }
        return false;
    }
    private void confirmDelete(long id) {
        new AlertDialog.Builder(this).setTitle("删除这条记录？").setMessage("将清空这条记录的明细，保留最少去重摘要。删除后无法撤销。")
                .setPositiveButton("删除", (d, w) -> mutate(() -> db.delete(id))).setNegativeButton("保留", null).show();
    }
    private void mutate(Runnable action) {
        worker.execute(() -> {
            try { action.run(); runOnUiThread(() -> { if (!isDestroyed()) render(); }); }
            catch (Exception error) { showFailure("保存未完成，请重试并保留应用数据"); }
        });
    }
    private void showFailure(String message) { runOnUiThread(() -> { if (!isDestroyed()) toast(message); }); }
    private void openSettings(Intent intent) {
        try { startActivity(intent); } catch (RuntimeException error) { toast("请在系统设置中手动开启对应权限"); }
    }
    private void sectionLabel(String number, String title) {
        spacer(content, 32); content.addView(text(number + "  /  " + title, 12, BLUE, true)); spacer(content, 12); rule(content);
    }
    private void settingRow(String title, String description, String actionLabel, Runnable action) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(16), 0, dp(16));
        LinearLayout copy = vertical(); copy.setPadding(0, 0, dp(12), 0); copy.addView(text(title, 15, INK, true));
        spacer(copy, 6); copy.addView(text(description, 12, MUTED, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button button = button(actionLabel, false); button.setTextSize(13); button.setTextColor(INK); button.setBackground(feedback(TINT, 6));
        button.setContentDescription(title + "，" + actionLabel); button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(dp(64), dp(48))); content.addView(row); rule(content);
    }
    private void action(LinearLayout parent, String title, boolean primary, Runnable action) {
        Button button = button(title, primary); button.setOnClickListener(v -> action.run()); parent.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setTextSize(14); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(48)); button.setElevation(0);
        button.setPadding(dp(10), 0, dp(10), 0); button.setStateListAnimator(null); button.setTextColor(primary ? PAPER : BLUE);
        button.setBackground(feedback(primary ? BLUE : Color.TRANSPARENT, 6)); return button;
    }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private RippleDrawable feedback(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(0x33E35B53), shape(color, Math.min(radius, 6)), shape(Color.WHITE, Math.min(radius, 6))); }
    private void rule(LinearLayout parent) {
        View rule = new View(this); rule.setBackgroundColor(LINE); parent.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    private void paragraph(LinearLayout parent, String value, int size) { spacer(parent, 8); parent.addView(text(value, size, MUTED, false)); }
    private void spacer(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private String money(long fen) { return BigDecimal.valueOf(fen, 2).toPlainString(); }
    private String directionName(String direction) {
        return switch (direction) { case "income" -> "收入"; case "refund" -> "退款"; case "transfer" -> "转账"; default -> "支出"; };
    }
}
