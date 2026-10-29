package dev.mr.localledger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

/**
 * Local character illustrations and independently drawn generic loot graphics.
 * Character references and generation details are recorded in docs/character-art.md.
 */
public final class TacticalVisuals {
    private static final int BG = Color.rgb(16, 20, 18);
    private static final int SURFACE = Color.rgb(25, 32, 29);
    private static final int INK = Color.rgb(241, 242, 236);
    private static final int MUTED = Color.rgb(154, 168, 158);
    private static final int RED = Color.rgb(227, 91, 83);
    private static final int LINE = Color.rgb(48, 58, 52);

    private TacticalVisuals() {}

    /** Decorative companion illustration. */
    public static View operator(Context context) {
        return operator(context, 0);
    }

    /** Companions: 0 furry scout, 1 Mai, 2 cat-ear partner. Unknown values use 0. */
    public static View operator(Context context, int variant) {
        int[] resources = { R.drawable.companion_furry, R.drawable.companion_mai,
                R.drawable.companion_catear };
        ImageView illustration = new ImageView(context);
        illustration.setImageResource(resources[variant >= 0 && variant < resources.length ? variant : 0]);
        illustration.setScaleType(ImageView.ScaleType.FIT_CENTER);
        illustration.setAdjustViewBounds(true);
        illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        illustration.setFocusable(false);
        illustration.setClickable(false);
        return illustration;
    }

    /** Decorative sealed equipment case and red crystal. Suggested size: 104 x 90 dp. */
    public static View loot(Context context) {
        return loot(context, "default");
    }

    /** Generic objects: watch, gold, diamond, case, crown, disk; otherwise crystal and case. */
    public static View loot(Context context, String kind) {
        return new IllustrationView(context, kind == null ? "default" : kind);
    }

    /** Cut-corner graphite surface. Emphasis adds one small red edge mark. */
    public static Drawable panel(Context context, boolean emphasis) {
        return new PanelDrawable(context, emphasis);
    }

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private static final class IllustrationView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String kind;

        IllustrationView(Context context, String kind) {
            super(context);
            this.kind = kind;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setFocusable(false);
            setClickable(false);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setStrokeJoin(Paint.Join.MITER);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredWidth = Math.round(dp(getContext(), 112));
            int desiredHeight = Math.round(dp(getContext(), 98));
            setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                    resolveSize(desiredHeight, heightMeasureSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float sourceWidth = 160;
            float sourceHeight = 140;
            float scale = Math.min(getWidth() / sourceWidth, getHeight() / sourceHeight);
            if (scale <= 0) return;
            canvas.save();
            canvas.translate((getWidth() - sourceWidth * scale) / 2f,
                    (getHeight() - sourceHeight * scale) / 2f);
            canvas.scale(scale, scale);
            switch (kind) {
                case "watch": drawWatch(canvas); break;
                case "gold": drawGold(canvas); break;
                case "diamond": drawDiamond(canvas); break;
                case "case": drawCase(canvas); break;
                case "crown": drawCrown(canvas); break;
                case "disk": drawDisk(canvas); break;
                default: drawLoot(canvas); break;
            }
            canvas.restore();
        }

        private void drawWatch(Canvas c) {
            ring(c, MUTED, 4, 80, 22, 11);
            roundRect(c, MUTED, 73, 28, 87, 41, 3);
            circle(c, Color.rgb(92, 107, 88), 80, 83, 47);
            circle(c, INK, 80, 81, 44);
            circle(c, SURFACE, 80, 81, 36);
            ring(c, MUTED, 1.2f, 80, 81, 30);
            for (int i = 0; i < 12; i++) {
                double angle = Math.PI * 2 * i / 12;
                stroke(c, MUTED, 2, (float) (80 + Math.sin(angle) * 29),
                        (float) (81 - Math.cos(angle) * 29),
                        (float) (80 + Math.sin(angle) * 32),
                        (float) (81 - Math.cos(angle) * 32));
            }
            stroke(c, INK, 3.2f, 66, 66, 80, 81, 97, 71);
            stroke(c, RED, 1.8f, 80, 87, 80, 56);
            circle(c, RED, 80, 81, 4);
        }

        private void drawGold(Canvas c) {
            goldBar(c, 23, 77, 69, 108);
            goldBar(c, 77, 77, 123, 108);
            goldBar(c, 50, 45, 96, 76);
        }

        private void goldBar(Canvas c, float left, float top, float right, float bottom) {
            polygon(c, Color.rgb(161, 164, 132), left, top, right, top,
                    right + 14, bottom - 10, right + 14, bottom, left - 10, bottom,
                    left - 10, bottom - 10);
            polygon(c, Color.rgb(235, 232, 197), left, top, right, top,
                    right + 8, bottom - 12, left - 5, bottom - 12);
            polygon(c, Color.rgb(192, 191, 152), left - 5, bottom - 12,
                    right + 8, bottom - 12, right + 14, bottom, left - 10, bottom);
            polygon(c, Color.rgb(123, 132, 105), right, top, right + 14, bottom - 10,
                    right + 14, bottom, right + 8, bottom - 12);
            stroke(c, INK, 1.2f, left + 4, top + 3, right - 3, top + 3);
            polygon(c, RED, left + 15, top + 10, left + 25, top + 10,
                    left + 29, top + 15, left + 17, top + 15);
        }

        private void drawDiamond(Canvas c) {
            polygon(c, RED, 45, 35, 115, 35, 142, 68, 80, 126, 18, 68);
            polygon(c, Color.rgb(250, 161, 138), 45, 35, 80, 35, 62, 68, 18, 68);
            polygon(c, Color.rgb(247, 123, 105), 80, 35, 115, 35, 102, 68, 62, 68);
            polygon(c, Color.rgb(168, 48, 43), 115, 35, 142, 68, 102, 68);
            polygon(c, Color.rgb(184, 47, 43), 102, 68, 142, 68, 80, 126);
            polygon(c, Color.rgb(228, 86, 74), 18, 68, 62, 68, 80, 126);
            stroke(c, Color.rgb(255, 190, 165), 1.5f, 45, 36, 19, 68, 102, 68);
        }

        private void drawCase(Canvas c) {
            roundRect(c, MUTED, 58, 24, 103, 48, 7);
            roundRect(c, BG, 65, 31, 96, 49, 3);
            polygon(c, Color.rgb(78, 95, 78), 27, 43, 125, 43, 138, 56,
                    138, 112, 129, 121, 25, 121, 20, 115, 20, 50);
            polygon(c, Color.rgb(119, 137, 112), 27, 43, 125, 43, 138, 56,
                    29, 56, 20, 50);
            polygon(c, SURFACE, 125, 43, 138, 56, 138, 112, 129, 121,
                    129, 58);
            stroke(c, LINE, 2, 22, 74, 128, 74);
            roundRect(c, INK, 43, 64, 54, 87, 2);
            roundRect(c, INK, 102, 64, 113, 87, 2);
            polygon(c, RED, 67, 91, 87, 91, 93, 97, 93, 108, 67, 108);
        }

        private void drawCrown(Canvas c) {
            polygon(c, Color.rgb(160, 169, 137), 23, 42, 56, 64, 81, 27,
                    106, 64, 139, 42, 126, 110, 35, 110);
            polygon(c, Color.rgb(227, 231, 197), 23, 42, 56, 64, 81, 27,
                    81, 95, 38, 95);
            polygon(c, Color.rgb(198, 209, 169), 81, 27, 106, 64, 139, 42,
                    123, 95, 81, 95);
            roundRect(c, MUTED, 34, 99, 127, 115, 3);
            polygon(c, RED, 81, 66, 91, 80, 81, 93, 71, 80);
            circle(c, INK, 23, 41, 5);
            circle(c, INK, 81, 26, 5);
            circle(c, INK, 139, 41, 5);
        }

        private void drawDisk(Canvas c) {
            polygon(c, Color.rgb(141, 156, 133), 40, 24, 112, 24, 129, 41,
                    129, 117, 31, 117, 31, 32);
            polygon(c, SURFACE, 112, 24, 129, 41, 129, 117, 122, 117, 122, 44);
            roundRect(c, INK, 49, 27, 104, 58, 2);
            roundRect(c, LINE, 82, 32, 95, 54, 1);
            roundRect(c, SURFACE, 44, 72, 112, 112, 3);
            stroke(c, MUTED, 2, 53, 83, 101, 83);
            stroke(c, MUTED, 2, 53, 92, 83, 92);
            roundRect(c, RED, 93, 93, 103, 103, 1);
        }

        private void drawLoot(Canvas c) {
            // Closed field case: chamfered lid, positive latches and no logos.
            polygon(c, BG, 55, 73, 107, 57, 149, 79, 149, 119, 98, 136, 55, 112);
            polygon(c, Color.rgb(72, 85, 74), 55, 70, 106, 54, 149, 77, 98, 94);
            polygon(c, Color.rgb(41, 51, 43), 55, 70, 98, 94, 98, 130, 55, 106);
            polygon(c, SURFACE, 98, 94, 149, 77, 149, 113, 98, 130);
            stroke(c, MUTED, 1.5f, 59, 72, 98, 94, 145, 79);
            stroke(c, LINE, 1.6f, 59, 84, 98, 107, 145, 91);
            polygon(c, Color.rgb(175, 187, 166), 70, 78, 76, 82, 76, 100,
                    70, 96);
            polygon(c, Color.rgb(175, 187, 166), 120, 88, 126, 86, 126, 103,
                    120, 105);
            polygon(c, BG, 91, 66, 107, 62, 119, 69, 115, 73, 106, 68, 96, 71);
            polygon(c, RED, 135, 100, 142, 98, 142, 107, 135, 109);

            // One prominent red specimen; the silhouette stays legible at small sizes.
            polygon(c, Color.rgb(104, 40, 37), 16, 79, 31, 41, 51, 27,
                    76, 46, 82, 87, 56, 116, 31, 111);
            polygon(c, RED, 31, 41, 51, 27, 63, 62, 54, 88, 16, 79);
            polygon(c, Color.rgb(249, 150, 127), 31, 41, 51, 27, 45, 60, 27, 71);
            polygon(c, Color.rgb(196, 58, 53), 51, 27, 76, 46, 63, 62);
            polygon(c, Color.rgb(130, 39, 37), 76, 46, 82, 87, 54, 88, 63, 62);
            polygon(c, Color.rgb(228, 88, 75), 16, 79, 54, 88, 31, 111);
            polygon(c, Color.rgb(157, 46, 41), 54, 88, 82, 87, 56, 116, 31, 111);
            stroke(c, Color.rgb(255, 184, 158), 1.3f, 31, 42, 51, 28, 62, 62);
            stroke(c, Color.rgb(249, 133, 112), 1.2f, 19, 79, 53, 87, 63, 63);
        }

        private void polygon(Canvas c, int color, float... vertices) {
            path.reset();
            path.moveTo(vertices[0], vertices[1]);
            for (int i = 2; i < vertices.length; i += 2) {
                path.lineTo(vertices[i], vertices[i + 1]);
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawPath(path, paint);
        }

        private void roundRect(Canvas c, int color, float left, float top, float right,
                float bottom, float radius) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        }

        private void circle(Canvas c, int color, float x, float y, float radius) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawCircle(x, y, radius, paint);
        }

        private void ring(Canvas c, int color, float width, float x, float y, float radius) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            c.drawCircle(x, y, radius, paint);
        }

        private void stroke(Canvas c, int color, float width, float... vertices) {
            path.reset();
            path.moveTo(vertices[0], vertices[1]);
            for (int i = 2; i < vertices.length; i += 2) {
                path.lineTo(vertices[i], vertices[i + 1]);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            c.drawPath(path, paint);
        }
    }

    private static final class PanelDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final float density;
        private final boolean emphasis;
        private int opacity = 255;
        private ColorFilter filter;

        PanelDrawable(Context context, boolean emphasis) {
            density = context.getResources().getDisplayMetrics().density;
            this.emphasis = emphasis;
            paint.setStrokeJoin(Paint.Join.MITER);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float inset = density * .5f;
            float left = bounds.left + inset;
            float top = bounds.top + inset;
            float right = bounds.right - inset;
            float bottom = bounds.bottom - inset;
            float cut = Math.min(12 * density, Math.min(bounds.width(), bounds.height()) / 5f);
            shape.reset();
            shape.moveTo(left, top);
            shape.lineTo(right - cut, top);
            shape.lineTo(right, top + cut);
            shape.lineTo(right, bottom);
            shape.lineTo(left + cut, bottom);
            shape.lineTo(left, bottom - cut);
            shape.close();

            setPaint(SURFACE, Paint.Style.FILL, 0);
            canvas.drawPath(shape, paint);
            canvas.save();
            canvas.clipPath(shape);
            // Surface texture lives at the right edge, leaving the text area completely calm.
            setPaint(Color.argb(24, 154, 168, 158), Paint.Style.STROKE, density * .65f);
            float grid = 20 * density;
            float gridStart = Math.max(left, right - 84 * density);
            for (float x = right; x >= gridStart; x -= grid) {
                canvas.drawLine(x, top, x, bottom, paint);
            }
            for (float y = top + grid; y < bottom; y += grid) {
                canvas.drawLine(gridStart, y, right, y, paint);
            }
            if (emphasis) {
                setPaint(RED, Paint.Style.FILL, 0);
                canvas.drawRect(left, top + 18 * density, left + 2 * density,
                        Math.min(bottom, top + 40 * density), paint);
            }
            canvas.restore();
            setPaint(LINE, Paint.Style.STROKE, density);
            canvas.drawPath(shape, paint);
        }

        private void setPaint(int color, Paint.Style style, float width) {
            paint.setStyle(style);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            paint.setAlpha(Math.round(Color.alpha(color) * opacity / 255f));
            paint.setColorFilter(filter);
        }

        @Override public void setAlpha(int alpha) {
            opacity = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            filter = colorFilter;
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
