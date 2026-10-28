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

/**
 * Original, local vector artwork for the field-ledger theme.
 * No game characters, insignia, screenshots or external assets are used.
 */
public final class TacticalVisuals {
    private static final int BG = Color.rgb(16, 20, 18);
    private static final int SURFACE = Color.rgb(25, 32, 29);
    private static final int INK = Color.rgb(241, 242, 236);
    private static final int MUTED = Color.rgb(154, 168, 158);
    private static final int RED = Color.rgb(227, 91, 83);
    private static final int LINE = Color.rgb(48, 58, 52);

    private TacticalVisuals() {}

    /** Decorative original chibi operator. Suggested size: 130 x 158 dp or 150 x 180 dp. */
    public static View operator(Context context) {
        return operator(context, 0);
    }

    /** Outfits: 0 scout, 1 support, 2 engineer. Unknown values use the scout outfit. */
    public static View operator(Context context, int variant) {
        return new IllustrationView(context, true, "default", variant >= 0 && variant <= 2 ? variant : 0);
    }

    /** Decorative sealed equipment case and red crystal. Suggested size: 104 x 90 dp. */
    public static View loot(Context context) {
        return loot(context, "default");
    }

    /** Generic objects: watch, gold, diamond, case, crown, disk; otherwise crystal and case. */
    public static View loot(Context context, String kind) {
        return new IllustrationView(context, false, kind == null ? "default" : kind, 0);
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
        private final boolean portrait;
        private final String kind;
        private final int variant;

        IllustrationView(Context context, boolean portrait, String kind, int variant) {
            super(context);
            this.portrait = portrait;
            this.kind = kind;
            this.variant = variant;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setFocusable(false);
            setClickable(false);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setStrokeJoin(Paint.Join.MITER);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredWidth = Math.round(dp(getContext(), portrait ? 138 : 112));
            int desiredHeight = Math.round(dp(getContext(), portrait ? 170 : 98));
            setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                    resolveSize(desiredHeight, heightMeasureSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float sourceWidth = portrait ? 180 : 160;
            float sourceHeight = portrait ? 220 : 140;
            float scale = Math.min(getWidth() / sourceWidth, getHeight() / sourceHeight);
            if (scale <= 0) return;
            canvas.save();
            canvas.translate((getWidth() - sourceWidth * scale) / 2f,
                    (getHeight() - sourceHeight * scale) / 2f);
            canvas.scale(scale, scale);
            if (portrait) {
                drawOperator(canvas);
            } else {
                switch (kind) {
                    case "watch": drawWatch(canvas); break;
                    case "gold": drawGold(canvas); break;
                    case "diamond": drawDiamond(canvas); break;
                    case "case": drawCase(canvas); break;
                    case "crown": drawCrown(canvas); break;
                    case "disk": drawDisk(canvas); break;
                    default: drawLoot(canvas); break;
                }
            }
            canvas.restore();
        }

        private void drawOperator(Canvas c) {
            // Soft proportions and a large visible face keep this original character approachable.
            roundRect(c, Color.rgb(8, 12, 10), 35, 204, 153, 213, 5);
            roundRect(c, LINE, 118, 132, 155, 184, 12);
            roundRect(c, variant == 1 ? Color.rgb(121, 64, 55) : Color.rgb(71, 84, 72),
                    128, 140, 152, 174, 7);
            if (variant == 0) stroke(c, MUTED, 2.5f, 142, 143, 142, 121);

            // Little boots and short coveralls, no weapons or rank insignia.
            roundRect(c, BG, 61, 179, 83, 210, 6);
            roundRect(c, BG, 104, 179, 126, 210, 6);
            int jacket = variant == 1 ? Color.rgb(188, 199, 175)
                    : variant == 2 ? Color.rgb(142, 153, 124) : Color.rgb(117, 133, 113);
            int vest = variant == 1 ? Color.rgb(116, 134, 113)
                    : variant == 2 ? Color.rgb(77, 85, 69) : Color.rgb(71, 88, 72);
            roundRect(c, jacket, 54, 125, 133, 190, 22);
            roundRect(c, vest, 65, 136, 120, 188, 12);
            polygon(c, Color.rgb(182, 194, 171), 74, 140, 85, 140, 77, 173, 68, 171);
            roundRect(c, LINE, 82, 155, 111, 177, 5);
            stroke(c, MUTED, 1.8f, 86, 161, 106, 161);
            roundRect(c, Color.rgb(195, 207, 182), 37, 140, 58, 172, 10);
            roundRect(c, Color.rgb(195, 207, 182), 129, 140, 148, 172, 10);
            roundRect(c, BG, 35, 162, 56, 178, 6);
            roundRect(c, BG, 131, 162, 150, 178, 6);
            stroke(c, Color.rgb(57, 72, 59), 2, 94, 180, 94, 192);

            if (variant == 0) {
                // Scout: broad red scarf and one loose end.
                polygon(c, Color.rgb(153, 53, 46), 104, 124, 125, 128, 138, 155,
                        123, 150, 118, 157, 108, 139);
                roundRect(c, RED, 56, 116, 133, 139, 11);
                stroke(c, Color.rgb(249, 151, 130), 2, 64, 123, 103, 125);
            } else if (variant == 1) {
                // Support: plain red supply pouch, no protected medical insignia.
                roundRect(c, LINE, 56, 118, 132, 136, 8);
                stroke(c, Color.rgb(207, 216, 192), 5, 63, 133, 108, 168);
                roundRect(c, Color.rgb(153, 53, 46), 102, 156, 146, 187, 6);
                roundRect(c, RED, 101, 153, 145, 180, 6);
                stroke(c, Color.rgb(255, 186, 161), 2, 109, 163, 137, 163);
                roundRect(c, INK, 111, 170, 122, 175, 1);
            } else {
                // Engineer: compact tools stay in a side pouch rather than in the hands.
                roundRect(c, LINE, 57, 117, 132, 135, 7);
                roundRect(c, RED, 64, 126, 78, 132, 2);
                roundRect(c, BG, 111, 160, 147, 191, 5);
                roundRect(c, Color.rgb(118, 132, 104), 110, 164, 146, 184, 3);
                stroke(c, INK, 4, 119, 167, 119, 150);
                ring(c, INK, 2.8f, 119, 147, 5);
                roundRect(c, RED, 130, 148, 137, 165, 2);
                stroke(c, MUTED, 2, 133.5f, 148, 133.5f, 141);
            }

            // All outfits share a warm, unobscured face; headwear changes the silhouette.
            roundRect(c, variant == 1 ? Color.rgb(68, 76, 61) : LINE,
                    29, 29, 146, 121, variant == 1 ? 35 : 40);
            roundRect(c, Color.rgb(237, 226, 202), 40, 61, 132, 125, 30);
            roundRect(c, Color.rgb(208, 198, 171), 111, 71, 135, 112, 14);
            if (variant == 0) drawScoutHeadwear(c);
            else if (variant == 1) drawSupportHeadwear(c);
            else drawEngineerHeadwear(c);

            // Two distinct eyes are readable even at 72 dp, with a slight left-facing bias.
            roundRect(c, BG, 53, 91, 62, 107, 4);
            roundRect(c, BG, 86, 90, 96, 106, 4);
            roundRect(c, INK, 55, 92, 58, 96, 1.5f);
            roundRect(c, INK, 88, 91, 91, 95, 1.5f);
            stroke(c, Color.rgb(88, 104, 84), 2, 69, 112, 77, 113, 82, 110);
            roundRect(c, Color.rgb(218, 164, 137), 46, 108, 57, 112, 2);
            roundRect(c, Color.rgb(218, 164, 137), 94, 107, 105, 111, 2);

            if (variant == 0) {
                // Simple radio geometry is not based on a named game character.
                roundRect(c, SURFACE, 124, 69, 151, 111, 12);
                roundRect(c, MUTED, 132, 78, 145, 102, 6);
                roundRect(c, RED, 137, 84, 141, 95, 2);
                stroke(c, LINE, 3, 132, 105, 122, 116, 107, 116);
                roundRect(c, LINE, 102, 112, 112, 119, 3);
            } else if (variant == 2) {
                roundRect(c, LINE, 126, 72, 149, 109, 9);
                roundRect(c, Color.rgb(194, 207, 172), 132, 79, 145, 102, 5);
            }
        }

        private void drawScoutHeadwear(Canvas c) {
            roundRect(c, Color.rgb(160, 176, 146), 29, 22, 147, 82, 30);
            polygon(c, Color.rgb(109, 131, 102), 98, 23, 129, 27, 144, 44,
                    147, 78, 116, 78, 111, 44);
            roundRect(c, Color.rgb(209, 218, 193), 43, 32, 103, 42, 5);
            polygon(c, Color.rgb(194, 208, 176), 21, 71, 122, 66, 143, 78,
                    139, 85, 25, 84, 17, 79);
            polygon(c, LINE, 26, 81, 129, 78, 133, 84, 30, 89);
        }

        private void drawSupportHeadwear(Canvas c) {
            roundRect(c, Color.rgb(93, 104, 80), 32, 23, 144, 77, 29);
            polygon(c, Color.rgb(93, 104, 80), 34, 52, 45, 48, 65, 43,
                    98, 41, 129, 52, 139, 80, 124, 94, 119, 69, 107, 67,
                    108, 81, 88, 67, 75, 71, 62, 83, 61, 69, 39, 86);
            stroke(c, Color.rgb(143, 158, 120), 2.4f, 47, 40, 69, 33, 105, 35);
            roundRect(c, LINE, 40, 44, 132, 68, 7);
            roundRect(c, MUTED, 47, 46, 82, 65, 5);
            roundRect(c, MUTED, 88, 46, 123, 65, 5);
            roundRect(c, Color.rgb(197, 212, 176), 51, 49, 78, 61, 3);
            roundRect(c, Color.rgb(197, 212, 176), 92, 49, 119, 61, 3);
            stroke(c, INK, 1.5f, 54, 58, 66, 51);
            stroke(c, INK, 1.5f, 96, 58, 108, 51);
        }

        private void drawEngineerHeadwear(Canvas c) {
            roundRect(c, Color.rgb(206, 216, 183), 34, 28, 142, 79, 24);
            polygon(c, Color.rgb(137, 154, 115), 102, 29, 127, 34, 140, 49,
                    142, 77, 119, 77, 116, 46);
            polygon(c, Color.rgb(236, 237, 205), 25, 69, 120, 64, 144, 76,
                    132, 86, 20, 84, 14, 78);
            polygon(c, LINE, 24, 82, 128, 79, 131, 84, 28, 88);
            roundRect(c, Color.rgb(137, 154, 115), 58, 36, 66, 64, 3);
            roundRect(c, Color.rgb(137, 154, 115), 79, 34, 87, 62, 3);
            roundRect(c, RED, 103, 48, 123, 61, 3);
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
