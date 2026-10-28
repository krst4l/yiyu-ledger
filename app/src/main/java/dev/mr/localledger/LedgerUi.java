package dev.mr.localledger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Original vector drawing primitives. No image assets, external fonts or chart libraries. */
public final class LedgerUi {
    private static final int ACCENT = Color.rgb(79, 99, 217);
    private static final int INK = Color.rgb(29, 41, 66);
    private static final int MUTED = Color.rgb(131, 144, 164);
    private static final int LINE = Color.rgb(232, 236, 243);

    private LedgerUi() {}

    /** Transparent backgroundColor draws only the icon. Caller supplies layout dimensions. */
    public static View icon(Context context, String name, int color, int backgroundColor) {
        return new IconView(context, name == null ? "other" : name, color, backgroundColor);
    }

    /** Daily confirmed CNY amounts. A zero day remains a genuine gap, never a decorative bar. */
    public static View chart(Context context, List<LedgerDb.DayTotal> days, boolean income) {
        return new DailyChart(context, days, income);
    }

    /** Exact ratio, clamped to the drawable range. Invalid/zero denominators show an empty track. */
    public static View categoryBar(Context context, long value, long max) {
        return new RatioBar(context, value, max);
    }

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private static float sp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().scaledDensity;
    }

    private static final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String name;
        private final int color;
        private final int backgroundColor;

        IconView(Context context, String name, int color, int backgroundColor) {
            super(context);
            this.name = name;
            this.color = color;
            this.backgroundColor = backgroundColor;
            setMinimumWidth(Math.round(dp(context, 36)));
            setMinimumHeight(Math.round(dp(context, 36)));
            setContentDescription(iconDescription(name));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0) return;
            float left = (getWidth() - size) / 2f;
            float top = (getHeight() - size) / 2f;
            if (Color.alpha(backgroundColor) > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(backgroundColor);
                canvas.drawRoundRect(left, top, left + size, top + size,
                        size * .3f, size * .3f, paint);
            }
            canvas.save();
            float glyph = size * (Color.alpha(backgroundColor) > 0 ? .61f : .78f);
            canvas.translate(left + (size - glyph) / 2f, top + (size - glyph) / 2f);
            canvas.scale(glyph / 24f, glyph / 24f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
            paint.setStrokeWidth(1.65f);
            drawIcon(canvas);
            canvas.restore();
        }

        private void drawIcon(Canvas c) {
            switch (name) {
                case "home":
                case "居家":
                    path(c, 3, 10, 12, 3, 21, 10);
                    path(c, 5, 9, 5, 21, 10, 21, 10, 14, 14, 14, 14, 21, 19, 21, 19, 9);
                    break;
                case "calendar":
                    roundRect(c, 4, 5, 20, 21, 3);
                    line(c, 4, 10, 20, 10);
                    line(c, 8, 3, 8, 7);
                    line(c, 16, 3, 16, 7);
                    line(c, 8, 14, 9, 14);
                    line(c, 15, 14, 16, 14);
                    line(c, 8, 18, 9, 18);
                    line(c, 15, 18, 16, 18);
                    break;
                case "chart":
                    path(c, 4, 3, 4, 21, 21, 21);
                    line(c, 9, 17, 9, 12);
                    line(c, 14, 17, 14, 7);
                    line(c, 19, 17, 19, 10);
                    break;
                case "user":
                    c.drawCircle(12, 7, 3.7f, paint);
                    c.drawArc(new RectF(4, 12, 20, 27), 188, 164, false, paint);
                    break;
                case "search":
                    c.drawCircle(10, 10, 6, paint);
                    line(c, 14.5f, 14.5f, 21, 21);
                    break;
                case "inbox":
                    path(c, 3, 14, 6, 5, 18, 5, 21, 14, 21, 20, 3, 20, 3, 14,
                            8, 14, 9.5f, 17, 14.5f, 17, 16, 14, 21, 14);
                    break;
                case "plus":
                    line(c, 12, 5, 12, 19);
                    line(c, 5, 12, 19, 12);
                    break;
                case "back":
                    path(c, 11, 5, 4, 12, 11, 19);
                    line(c, 4, 12, 21, 12);
                    break;
                case "edit":
                    path(c, 5, 15, 16, 4, 20, 8, 9, 19, 4, 20, 5, 15);
                    line(c, 13.5f, 6.5f, 17.5f, 10.5f);
                    break;
                case "餐饮":
                    line(c, 5, 3, 5, 9);
                    line(c, 8, 3, 8, 9);
                    line(c, 11, 3, 11, 9);
                    path(c, 5, 8, 5, 10, 8, 12, 11, 10, 11, 8);
                    line(c, 8, 12, 8, 21);
                    path(c, 19, 21, 19, 3, 16, 6, 16, 12, 19, 12);
                    break;
                case "购物":
                    roundRect(c, 4, 7, 20, 21, 2);
                    c.drawArc(new RectF(8, 2, 16, 12), 180, 180, false, paint);
                    break;
                case "交通":
                    roundRect(c, 5, 3, 19, 19, 3);
                    roundRect(c, 7, 6, 17, 12, 1);
                    line(c, 8, 16, 8.3f, 16);
                    line(c, 15.7f, 16, 16, 16);
                    line(c, 8, 19, 7, 22);
                    line(c, 16, 19, 17, 22);
                    break;
                case "医疗":
                    roundRect(c, 3, 6, 21, 21, 3);
                    roundRect(c, 8, 3, 16, 7, 1.4f);
                    line(c, 12, 10, 12, 17);
                    line(c, 8.5f, 13.5f, 15.5f, 13.5f);
                    break;
                case "教育":
                    path(c, 3, 5, 8, 5, 12, 7, 16, 5, 21, 5, 21, 20, 16, 20, 12, 22, 8, 20, 3, 20, 3, 5);
                    line(c, 12, 7, 12, 22);
                    break;
                case "娱乐":
                    roundRect(c, 3, 7, 21, 18, 5);
                    line(c, 7, 10, 7, 15);
                    line(c, 4.5f, 12.5f, 9.5f, 12.5f);
                    c.drawCircle(16, 11, .6f, paint);
                    c.drawCircle(18.5f, 14, .6f, paint);
                    break;
                case "人情":
                    roundRect(c, 4, 10, 20, 21, 1);
                    roundRect(c, 3, 7, 21, 11, 1);
                    line(c, 12, 7, 12, 21);
                    c.drawOval(new RectF(6, 2.5f, 12, 7), paint);
                    c.drawOval(new RectF(12, 2.5f, 18, 7), paint);
                    break;
                case "旅行":
                    roundRect(c, 4, 6, 20, 20, 3);
                    roundRect(c, 9, 3, 15, 6, 1);
                    line(c, 8, 9, 8, 17);
                    line(c, 16, 9, 16, 17);
                    line(c, 8, 20, 8, 22);
                    line(c, 16, 20, 16, 22);
                    break;
                case "数码":
                    roundRect(c, 6, 2.5f, 18, 21.5f, 2.5f);
                    line(c, 10, 5.5f, 14, 5.5f);
                    line(c, 11, 18.5f, 13, 18.5f);
                    break;
                default:
                    roundRect(c, 4, 4, 10, 10, 1.3f);
                    roundRect(c, 14, 4, 20, 10, 1.3f);
                    roundRect(c, 4, 14, 10, 20, 1.3f);
                    roundRect(c, 14, 14, 20, 20, 1.3f);
                    break;
            }
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2) {
            c.drawLine(x1, y1, x2, y2, paint);
        }

        private void path(Canvas c, float... points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i + 1 < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            c.drawPath(path, paint);
        }

        private void roundRect(Canvas c, float left, float top, float right, float bottom, float radius) {
            c.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        }
    }

    private static String iconDescription(String name) {
        switch (name) {
            case "home": return "账本";
            case "calendar": return "日历";
            case "chart": return "统计";
            case "user": return "我的";
            case "search": return "搜索";
            case "inbox": return "待核对";
            case "plus": return "新增账目";
            case "back": return "返回";
            case "edit": return "编辑账目";
            case "other": return "其他";
            default: return name;
        }
    }

    private static final class RatioBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final double fraction;

        RatioBar(Context context, long value, long max) {
            super(context);
            fraction = value <= 0 || max <= 0 ? 0d : Math.min(1d, (double) value / max);
            setMinimumHeight(Math.round(dp(context, 5)));
            setContentDescription(String.format(Locale.CHINA, "金额占比 %.1f%%", fraction * 100));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
            float height = Math.min(dp(getContext(), 5), getHeight() - getPaddingTop() - getPaddingBottom());
            if (right <= left || height <= 0) return;
            float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - height) / 2f;
            paint.setColor(LINE);
            canvas.drawRoundRect(left, top, right, top + height, height / 2, height / 2, paint);
            if (fraction > 0) {
                paint.setColor(ACCENT);
                canvas.drawRoundRect(left, top, left + (right - left) * (float) fraction,
                        top + height, height / 2, height / 2, paint);
            }
        }
    }

    private static final class DailyChart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Long> values = new ArrayList<>();
        private final List<Integer> labels = new ArrayList<>();
        private final long max;
        private final boolean income;

        DailyChart(Context context, List<LedgerDb.DayTotal> days, boolean income) {
            super(context);
            this.income = income;
            long highest = 0;
            BigDecimal sum = BigDecimal.ZERO;
            int nonzero = 0;
            StringBuilder details = new StringBuilder();
            if (days != null) {
                for (LedgerDb.DayTotal day : days) {
                    if (day == null || day.day < 1 || day.day > 31 || labels.size() >= 31) continue;
                    long value = Math.max(0, income ? day.incomeFen : day.expenseFen);
                    labels.add(day.day);
                    values.add(value);
                    highest = Math.max(highest, value);
                    sum = sum.add(BigDecimal.valueOf(value, 2));
                    if (value > 0) {
                        nonzero++;
                        details.append(day.day).append("日 ").append(LedgerDb.money(value)).append("元；");
                    }
                }
            }
            max = highest;
            setMinimumHeight(Math.round(dp(context, 192)));
            String title = income ? "每日收入" : "每日支出";
            setContentDescription(highest == 0 ? title + "图表，本月暂无已确认记录"
                    : title + "柱状图，" + nonzero + "天有记录，合计"
                    + sum.toPlainString() + "元，单日最高"
                    + LedgerDb.money(highest) + "元。" + details);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float textSize = sp(getContext(), 10);
            float left = getPaddingLeft() + dp(getContext(), 45);
            float right = getWidth() - getPaddingRight() - dp(getContext(), 5);
            float top = getPaddingTop() + dp(getContext(), 20);
            float bottom = getHeight() - getPaddingBottom() - dp(getContext(), 26);
            if (right <= left || bottom <= top) return;
            paint.setStrokeWidth(Math.max(1, density * .7f));
            paint.setTextSize(textSize);
            paint.setTextAlign(Paint.Align.RIGHT);
            // Use the actual maximum as the scale so small amounts are never disguised as empty.
            double ceiling = max > 0 ? max : 100d;
            for (int row = 0; row < 3; row++) {
                float y = bottom - (bottom - top) * row / 2f;
                paint.setColor(LINE);
                canvas.drawLine(left, y, right, y, paint);
                paint.setColor(MUTED);
                String label = max == 0 && row > 0 ? "" : axisLabel(ceiling * row / 2 / 100);
                canvas.drawText(label, left - dp(getContext(), 8), y + textSize * .35f, paint);
            }
            if (max == 0) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(sp(getContext(), 12));
                paint.setColor(MUTED);
                canvas.drawText(income ? "暂无已确认收入" : "暂无已确认支出", (left + right) / 2,
                        (top + bottom) / 2 + sp(getContext(), 4), paint);
            }
            int count = values.size();
            if (count == 0) return;
            float slot = (right - left) / count;
            float barWidth = Math.min(dp(getContext(), 11), slot * .56f);
            for (int i = 0; i < count; i++) {
                float x = left + slot * (i + .5f);
                long value = values.get(i);
                if (value > 0 && max > 0) {
                    float height = (float) ((double) value / ceiling * (bottom - top));
                    paint.setColor(ACCENT);
                    canvas.drawRoundRect(x - barWidth / 2, bottom - height, x + barWidth / 2,
                            bottom, Math.min(barWidth / 2, dp(getContext(), 2)),
                            Math.min(barWidth / 2, dp(getContext(), 2)), paint);
                }
                int day = labels.get(i);
                if (count <= 10 || i == 0 || i == count - 1 || day % 5 == 0 && i < count - 2) {
                    paint.setColor(MUTED);
                    paint.setTextSize(textSize);
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(Integer.toString(day), x, bottom + dp(getContext(), 18), paint);
                }
            }
            paint.setColor(INK);
            paint.setTextSize(sp(getContext(), 10));
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("元", getPaddingLeft(), top - dp(getContext(), 6), paint);
        }

        private static String axisLabel(double yuan) {
            if (yuan >= 100_000_000d) return String.format(Locale.CHINA, "%.1f亿", yuan / 100_000_000d);
            if (yuan >= 10_000d) return String.format(Locale.CHINA, "%.1f万", yuan / 10_000d);
            if (yuan >= 1000d) return String.format(Locale.CHINA, "%.1fk", yuan / 1000d);
            // Half of an odd fen creates a .005-yuan tick: retain it instead of rounding a
            // 10.50-yuan maximum to 11 or showing identical labels for a one-fen day.
            return BigDecimal.valueOf(yuan).setScale(3, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
    }
}
