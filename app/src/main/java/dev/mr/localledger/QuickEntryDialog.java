package dev.mr.localledger;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A local entry draft. No database write occurs until SaveHandler accepts it. */
public final class QuickEntryDialog extends Dialog {
    private static final int BLUE = Color.rgb(79, 99, 217);
    private static final int INK = Color.rgb(29, 41, 66);
    private static final int MUTED = Color.rgb(110, 122, 145);
    private static final int BACKGROUND = Color.rgb(245, 247, 251);
    private static final int PALE_BLUE = Color.rgb(232, 236, 255);
    private static final long MAX_AMOUNT_FEN = 999_999_999_99L;
    private static final String[] DIRECTIONS = {"expense", "income", "refund", "transfer"};
    private static final String[] DIRECTION_LABELS = {"支出", "收入", "退款", "转账"};

    public interface Completion { void finish(boolean success); }

    public interface SaveHandler {
        void save(long amountFen, String direction, String merchant, String category,
                  String note, long transactionAt, Completion completion);
    }

    private final Activity activity;
    private final SaveHandler saveHandler;
    private final long entryId;
    private final boolean needsTimeConfirmation;
    private final List<View> formControls = new ArrayList<>();
    private String amount;
    private String direction;
    private String merchant;
    private String category;
    private String note;
    private long transactionAt;
    private boolean timeConfirmed;
    private boolean saving;
    private int saveAttempt;
    private TextView amountView;
    private TextView errorView;
    private TextView dateButton;
    private TextView timeButton;
    private TextView saveButton;
    private EditText merchantInput;
    private EditText noteInput;
    private CheckBox timeConfirmation;
    private LinearLayout directionRow;
    private LinearLayout categoryGrid;
    private LinearLayout root;
    private LinearLayout keypad;
    private ScrollView pageScroll;
    private ScrollView scroll;

    public QuickEntryDialog(Activity activity, LedgerDb.Entry existing, Bundle restoredDraft,
                            long initialTime, SaveHandler handler) {
        super(activity, android.R.style.Theme_Material_Light_NoActionBar);
        if (handler == null) throw new IllegalArgumentException("SaveHandler is required");
        this.activity = activity;
        this.saveHandler = handler;
        Bundle draft = restoredDraft == null ? new Bundle() : restoredDraft;
        entryId = draft.getLong("entry_id", existing == null ? -1 : existing.id);
        boolean unknownTime = draft.getBoolean("needs_time", existing != null && existing.needsTimeConfirmation);
        long fallbackTime = existing == null ? initialTime : existing.createdAt;
        transactionAt = draft.getLong("transaction_at", fallbackTime);
        boolean resetInvalidTime = false;
        if (transactionAt <= 0 || transactionAt > System.currentTimeMillis() + 300_000L) {
            unknownTime = true;
            resetInvalidTime = true;
            transactionAt = System.currentTimeMillis();
        }
        needsTimeConfirmation = unknownTime;
        timeConfirmed = !resetInvalidTime && draft.getBoolean("time_confirmed", !needsTimeConfirmation);
        String existingAmount = existing == null ? "" : BigDecimal.valueOf(existing.amountFen, 2).toPlainString();
        amount = draft.containsKey("amount") ? draft.getString("amount", "")
                : draft.containsKey("amount_fen") ? BigDecimal.valueOf(draft.getLong("amount_fen"), 2).toPlainString()
                : existingAmount;
        if (!amount.isEmpty() && !amount.matches("[0-9]{1,9}(?:\\.[0-9]{0,2})?")) amount = "";
        direction = draft.getString("direction", existing == null ? "expense" : existing.direction);
        if (!isDirection(direction)) direction = "expense";
        merchant = limited(draft.getString("merchant", existing == null ? "" : existing.merchant), 120);
        category = limited(draft.getString("category", existing == null ? "" : existing.category), 60);
        note = limited(draft.getString("note", existing == null ? "" : existing.note), 500);
        setCanceledOnTouchOutside(false);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BACKGROUND));
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
            if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }

        root = vertical();
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        pageScroll = new ScrollView(activity);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        root.addView(pageScroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = vertical();
        pageScroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout heading = vertical();
        heading.setPadding(dp(20), dp(4), dp(20), dp(8));
        page.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = horizontal();
        TextView close = button("返回", INK, Color.TRANSPARENT, 15);
        compactButton(close, 15);
        close.setContentDescription("关闭记账窗口");
        close.setOnClickListener(v -> { if (!saving) dismiss(); });
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(48)));
        TextView title = text(entryId >= 0 ? "编辑账目" : "记一笔", 24, INK, true);
        title.setSingleLine(true);
        title.setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        heading.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        directionRow = horizontal();
        LinearLayout.LayoutParams directions = new LinearLayout.LayoutParams(-1, dp(48));
        directions.topMargin = dp(4);
        heading.addView(directionRow, directions);
        drawDirections();

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout content = vertical();
        content.setPadding(dp(20), dp(8), dp(20), dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        content.addView(text("分类", 15, INK, true));
        categoryGrid = vertical();
        addSection(content, categoryGrid, 8);
        drawCategories();

        addSection(content, text("交易时间", 15, INK, true), 20);
        LinearLayout dateRow = horizontal();
        dateButton = button("", INK, Color.WHITE, 14);
        timeButton = button("", INK, Color.WHITE, 14);
        dateButton.setOnClickListener(v -> chooseDate());
        timeButton.setOnClickListener(v -> chooseTime());
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(0, -2, 1.8f);
        dateParams.setMargins(0, 0, dp(8), 0);
        dateRow.addView(dateButton, dateParams);
        dateRow.addView(timeButton, new LinearLayout.LayoutParams(0, -2, 1));
        addSection(content, dateRow, 8);
        refreshTime();
        if (needsTimeConfirmation) {
            TextView explanation = text("来源日期不明确，请核对日期与时间后确认。", 13, BLUE, false);
            addSection(content, explanation, 10);
            timeConfirmation = new CheckBox(activity);
            timeConfirmation.setText("我已核对上方交易日期与时间");
            timeConfirmation.setTextColor(INK);
            timeConfirmation.setTextSize(14);
            timeConfirmation.setMinHeight(dp(48));
            timeConfirmation.setButtonTintList(ColorStateList.valueOf(BLUE));
            timeConfirmation.setChecked(timeConfirmed);
            timeConfirmation.setOnCheckedChangeListener((view, checked) -> timeConfirmed = checked);
            formControls.add(timeConfirmation);
            content.addView(timeConfirmation, new LinearLayout.LayoutParams(-1, -2));
        }

        merchantInput = field("商户 / 对方", merchant, 120, false);
        addSection(content, merchantInput, 18);
        noteInput = field("备注（选填）", note, 500, true);
        addSection(content, noteInput, 10);

        LinearLayout bottom = vertical();
        bottom.setPadding(dp(20), dp(4), dp(20), dp(8));
        page.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        errorView = text("", 13, Color.rgb(169, 48, 62), false);
        errorView.setPadding(0, dp(4), 0, dp(4));
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(View.GONE);
        bottom.addView(errorView);
        LinearLayout amountRow = horizontal();
        TextView currency = text("¥", 24, INK, false);
        currency.setPadding(0, 0, dp(8), 0);
        amountRow.addView(currency);
        amountView = text("", 32, INK, true);
        amountView.setSingleLine(true);
        amountView.setHorizontallyScrolling(false);
        amountView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        amountView.setAutoSizeTextTypeUniformWithConfiguration(14, 32, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        amountView.setOnClickListener(v -> hideTextKeyboard());
        formControls.add(amountView);
        amountRow.addView(amountView, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView clear = button("清空", BLUE, Color.TRANSPARENT, 14);
        compactButton(clear, 14);
        clear.setContentDescription("清空金额");
        clear.setOnClickListener(v -> { hideTextKeyboard(); changeAmount(""); });
        amountRow.addView(clear, new LinearLayout.LayoutParams(dp(64), dp(48)));
        bottom.addView(amountRow, new LinearLayout.LayoutParams(-1, dp(48)));
        refreshAmount();
        keypad = vertical();
        bottom.addView(keypad, new LinearLayout.LayoutParams(-1, -2));
        buildKeypad(keypad);
        saveButton = button("保存账目", Color.WHITE, BLUE, 17);
        compactButton(saveButton, 17);
        saveButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        saveButton.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(48));
        saveParams.topMargin = dp(8);
        bottom.addView(saveButton, saveParams);

        pageScroll.addOnLayoutChangeListener((view, left, top, right, lower,
                previousLeft, previousTop, previousRight, previousLower) -> fitAvailableHeight(heading, bottom));
        bottom.addOnLayoutChangeListener((view, left, top, right, lower,
                previousLeft, previousTop, previousRight, previousLower) -> fitAvailableHeight(heading, bottom));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottomInset;
            boolean keyboardVisible;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottomInset = Math.max(bars.bottom, ime.bottom);
                keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottomInset = insets.getSystemWindowInsetBottom();
                keyboardVisible = bottomInset > dp(100);
            }
            boolean changed = view.getPaddingLeft() != left || view.getPaddingTop() != top
                    || view.getPaddingRight() != right || view.getPaddingBottom() != bottomInset
                    || (keypad.getVisibility() == View.GONE) != keyboardVisible;
            view.setPadding(left, top, right, bottomInset);
            keypad.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
            if (changed && keyboardVisible) revealFocusedField();
            return insets;
        });
        root.requestFocus();
    }

    private void fitAvailableHeight(LinearLayout heading, LinearLayout bottom) {
        int available = pageScroll.getHeight() - pageScroll.getPaddingTop() - pageScroll.getPaddingBottom();
        if (available <= 0 || heading.getMeasuredHeight() <= 0 || bottom.getMeasuredHeight() <= 0) return;
        int middleHeight = available - heading.getMeasuredHeight() - bottom.getMeasuredHeight();
        // With enough space the page exactly fills its viewport, leaving only the form scrollable.
        // Short windows expand the whole form so the outer page can reach every field and Save.
        int desired = middleHeight < dp(160) ? ViewGroup.LayoutParams.WRAP_CONTENT : middleHeight;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        if (params.height == desired) return;
        params.height = desired;
        scroll.setLayoutParams(params);
        revealFocusedField();
    }

    @Override public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (root != null) root.requestApplyInsets();
    }

    public Bundle snapshot() {
        Bundle state = new Bundle();
        state.putLong("entry_id", entryId);
        state.putBoolean("needs_time", needsTimeConfirmation);
        state.putString("amount", amount);
        state.putString("direction", direction);
        state.putString("merchant", merchantInput == null ? merchant : merchantInput.getText().toString());
        state.putString("category", category);
        state.putString("note", noteInput == null ? note : noteInput.getText().toString());
        state.putLong("transaction_at", transactionAt);
        state.putBoolean("time_confirmed", timeConfirmation == null ? timeConfirmed : timeConfirmation.isChecked());
        return state;
    }

    public boolean isSaving() { return saving; }

    private void drawDirections() {
        unregisterControls(directionRow);
        directionRow.removeAllViews();
        for (int i = 0; i < DIRECTIONS.length; i++) {
            final String selectedDirection = DIRECTIONS[i];
            boolean selected = selectedDirection.equals(direction);
            TextView choice = button(DIRECTION_LABELS[i], selected ? Color.WHITE : MUTED,
                    selected ? INK : Color.WHITE, 15);
            compactButton(choice, 15);
            choice.setSelected(selected);
            choice.setContentDescription(DIRECTION_LABELS[i] + (selected ? "，已选择" : ""));
            choice.setOnClickListener(v -> {
                if (saving) return;
                direction = selectedDirection;
                drawDirections();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) params.setMargins(dp(6), 0, 0, 0);
            directionRow.addView(choice, params);
        }
    }

    private void drawCategories() {
        unregisterControls(categoryGrid);
        categoryGrid.removeAllViews();
        Set<String> names = new LinkedHashSet<>();
        for (String name : new LedgerPreferences(activity).categories()) {
            String safe = limited(name, 60).trim();
            if (!safe.isEmpty()) names.add(safe);
        }
        if (!category.isEmpty()) names.add(category);
        if (names.isEmpty()) names.add("其他");
        if (category.isEmpty()) category = names.iterator().next();
        float fontScale = activity.getResources().getConfiguration().fontScale;
        int width = activity.getResources().getConfiguration().screenWidthDp;
        int columns = fontScale > 1.6f || width < 320 ? 2 : fontScale > 1.15f ? 3 : 4;
        List<String> items = new ArrayList<>(names);
        for (int offset = 0; offset < items.size(); offset += columns) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.TOP);
            for (int col = 0; col < columns; col++) {
                int index = offset + col;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (col > 0) params.setMargins(dp(6), 0, 0, 0);
                if (index >= items.size()) { row.addView(new View(activity), params); continue; }
                String name = items.get(index);
                boolean selected = name.equals(category);
                LinearLayout item = vertical();
                item.setGravity(Gravity.CENTER);
                item.setPadding(dp(4), dp(10), dp(4), dp(9));
                item.setMinimumHeight(dp(88));
                item.setBackground(surface(selected ? PALE_BLUE : Color.TRANSPARENT, selected ? BLUE : Color.TRANSPARENT, 16));
                item.setClickable(true);
                item.setFocusable(true);
                item.setSelected(selected);
                item.setContentDescription(name + (selected ? "，已选择分类" : "，选择分类"));
                View icon = LedgerUi.icon(activity, name, selected ? BLUE : MUTED, selected ? Color.WHITE : Color.rgb(235, 239, 246));
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                item.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView label = text(name, 13, selected ? BLUE : INK, selected);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(0, dp(6), 0, 0);
                label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                item.addView(label, new LinearLayout.LayoutParams(-1, -2));
                item.setOnClickListener(v -> { if (!saving) { category = name; drawCategories(); } });
                formControls.add(item);
                row.addView(item, params);
            }
            addSection(categoryGrid, row, offset == 0 ? 0 : 6);
        }
    }

    private void buildKeypad(LinearLayout content) {
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {".", "0", "⌫"}};
        int height = dp(activity.getResources().getConfiguration().screenHeightDp < 700 ? 44 : 48);
        int rowIndex = 0;
        for (String[] keyRow : keys) {
            LinearLayout row = horizontal();
            for (int column = 0; column < keyRow.length; column++) {
                String key = keyRow[column];
                TextView digit = button(key, INK, Color.WHITE, 23);
                compactButton(digit, 23);
                if ("⌫".equals(key)) digit.setContentDescription("删除最后一位金额");
                else if (".".equals(key)) digit.setContentDescription("小数点");
                digit.setOnClickListener(v -> enterKey(key));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1);
                if (column > 0) params.setMargins(dp(8), 0, 0, 0);
                row.addView(digit, params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, height);
            if (rowIndex++ > 0) rowParams.topMargin = dp(4);
            content.addView(row, rowParams);
        }
    }

    private void enterKey(String key) {
        if (saving) return;
        hideTextKeyboard();
        if ("⌫".equals(key)) {
            changeAmount(amount.isEmpty() ? "" : amount.substring(0, amount.length() - 1));
            return;
        }
        if (".".equals(key)) {
            if (!amount.contains(".")) changeAmount(amount.isEmpty() ? "0." : amount + ".");
            return;
        }
        String candidate = "0".equals(amount) ? key : amount + key;
        if (!candidate.matches("[0-9]{1,9}(?:\\.[0-9]{0,2})?")) return;
        changeAmount(candidate);
    }

    private void changeAmount(String value) {
        if (saving) return;
        amount = value;
        refreshAmount();
        if (errorView != null) errorView.setVisibility(View.GONE);
    }

    private void refreshAmount() {
        if (amountView == null) return;
        amountView.setText(amount.isEmpty() ? "0.00" : amount);
        amountView.setTextColor(amount.isEmpty() ? MUTED : INK);
        amountView.setContentDescription("金额 " + (amount.isEmpty() ? "0" : amount) + " 元");
    }

    private void hideTextKeyboard() {
        View focus = getCurrentFocus();
        if (!(focus instanceof EditText)) return;
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
        if (root != null) root.requestFocus();
    }

    private void revealFocusedField() {
        if (scroll == null) return;
        scroll.post(() -> {
            View focus = getCurrentFocus();
            if (focus != merchantInput && focus != noteInput) return;
            focus.requestRectangleOnScreen(new Rect(0, 0, focus.getWidth(), focus.getHeight() + dp(12)), false);
        });
    }

    private void chooseDate() {
        if (saving) return;
        Calendar selected = calendar();
        DatePickerDialog picker = new DatePickerDialog(activity, (view, year, month, day) -> {
            if (saving) return;
            Calendar value = calendar();
            value.set(year, month, day);
            transactionAt = value.getTimeInMillis();
            clearTimeConfirmation();
            refreshTime();
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(1L);
        picker.getDatePicker().setMaxDate(System.currentTimeMillis() + 300_000L);
        picker.show();
    }

    private void chooseTime() {
        if (saving) return;
        Calendar selected = calendar();
        new TimePickerDialog(activity, (view, hour, minute) -> {
            if (saving) return;
            Calendar value = calendar();
            value.set(Calendar.HOUR_OF_DAY, hour);
            value.set(Calendar.MINUTE, minute);
            value.set(Calendar.SECOND, 0);
            value.set(Calendar.MILLISECOND, 0);
            transactionAt = value.getTimeInMillis();
            clearTimeConfirmation();
            refreshTime();
        }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show();
    }

    private void clearTimeConfirmation() {
        if (needsTimeConfirmation) {
            timeConfirmed = false;
            if (timeConfirmation != null) timeConfirmation.setChecked(false);
        }
    }

    private Calendar calendar() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(transactionAt);
        return calendar;
    }

    private void refreshTime() {
        if (dateButton == null || timeButton == null) return;
        dateButton.setText(new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(transactionAt));
        timeButton.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(transactionAt));
        dateButton.setContentDescription("选择交易日期，当前" + dateButton.getText());
        timeButton.setContentDescription("选择交易时间，当前" + timeButton.getText());
    }

    private void save() {
        if (saving) return;
        long fen;
        try {
            if (!amount.matches("[0-9]{1,9}(?:\\.[0-9]{0,2})?")) throw new NumberFormatException();
            fen = new BigDecimal(amount).movePointRight(2).longValueExact();
            if (fen <= 0 || fen > MAX_AMOUNT_FEN) throw new NumberFormatException();
        } catch (ArithmeticException | NumberFormatException invalid) {
            showError("请输入大于 0 且不超过 999999999.99 元的金额。");
            return;
        }
        if (transactionAt <= 0 || transactionAt > System.currentTimeMillis() + 300_000L) {
            showError("请核对交易日期，时间不能晚于当前时间 5 分钟。");
            return;
        }
        if (needsTimeConfirmation && (timeConfirmation == null || !timeConfirmation.isChecked())) {
            showError("请先勾选确认交易日期与时间。");
            if (timeConfirmation != null) scroll.post(() -> timeConfirmation.requestRectangleOnScreen(
                    new Rect(0, 0, timeConfirmation.getWidth(), timeConfirmation.getHeight()), false));
            return;
        }
        merchant = merchantInput.getText().toString().trim();
        note = noteInput.getText().toString().trim();
        category = category.trim();
        if (merchant.length() > 120 || category.isEmpty() || category.length() > 60 || note.length() > 500) {
            showError("请检查商户、分类和备注长度。");
            return;
        }
        errorView.setVisibility(View.GONE);
        setSaving(true);
        final int attempt = ++saveAttempt;
        try {
            saveHandler.save(fen, direction, merchant, category, note, transactionAt, success -> {
                if (!saving || saveAttempt != attempt) return;
                setSaving(false);
                if (success) dismiss();
                else showError("未能保存，内容已保留，请重试。");
            });
        } catch (RuntimeException failure) {
            if (saving && saveAttempt == attempt) {
                setSaving(false);
                showError("未能保存，内容已保留，请重试。");
            }
        }
    }

    private void setSaving(boolean active) {
        saving = active;
        setCancelable(!active);
        for (View control : formControls) control.setEnabled(!active);
        if (saveButton != null) saveButton.setText(active ? "正在保存…" : "保存账目");
    }

    private void unregisterControls(View view) {
        formControls.remove(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) unregisterControls(group.getChildAt(i));
    }

    private void showError(String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
        errorView.announceForAccessibility(message);
        errorView.post(() -> errorView.requestRectangleOnScreen(
                new Rect(0, 0, errorView.getWidth(), errorView.getHeight()), false));
    }

    private EditText field(String hint, String value, int limit, boolean multiline) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setText(value);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        input.setMinHeight(dp(54));
        input.setBackground(surface(Color.WHITE, Color.TRANSPARENT, 16));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.setSingleLine(!multiline);
        if (multiline) { input.setMinLines(2); input.setMaxLines(5); input.setGravity(Gravity.TOP | Gravity.START); }
        input.setOnFocusChangeListener((view, focused) -> { if (focused) revealFocusedField(); });
        formControls.add(input);
        return input;
    }

    private void compactButton(TextView button, int maximumSize) {
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setSingleLine(true);
        button.setAutoSizeTextTypeUniformWithConfiguration(12, maximumSize, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    }

    private TextView button(String label, int color, int background, int size) {
        TextView button = text(label, size, color, false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), dp(12), dp(8), dp(12));
        button.setMinHeight(dp(48));
        button.setBackground(surface(background, Color.TRANSPARENT, 16));
        button.setClickable(true);
        button.setFocusable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        formControls.add(button);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private RippleDrawable surface(int color, int stroke, int radius) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) fill.setStroke(dp(1), stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28, 79, 99, 217)), fill, mask);
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void addSection(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private static boolean isDirection(String value) {
        for (String candidate : DIRECTIONS) if (candidate.equals(value)) return true;
        return false;
    }

    private static String limited(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
