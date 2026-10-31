package org.privacybubble.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import org.privacybubble.app.R;

/** Semantic native UI primitives shared across the app's product screens. */
public final class AppTheme {
    public final Context context;
    public final int background, surface, subtle, text, muted, accent, onAccent, border, warning, danger, success;

    public AppTheme(Context context) {
        this.context = context;
        background = context.getColor(R.color.pb_background); surface = context.getColor(R.color.pb_surface);
        subtle = context.getColor(R.color.pb_subtle); text = context.getColor(R.color.pb_text);
        muted = context.getColor(R.color.pb_muted); accent = context.getColor(R.color.pb_accent);
        onAccent = context.getColor(R.color.pb_on_accent); border = context.getColor(R.color.pb_border);
        warning = context.getColor(R.color.pb_warning); danger = context.getColor(R.color.pb_danger);
        success = context.getColor(R.color.pb_success);
    }

    public int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    public TextView text(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setLineSpacing(dp(2), 1.08f);
        view.setIncludeFontPadding(true);
        return view;
    }

    public TextView heading(String value, int size) {
        TextView view = text(value, size, text); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ViewCompat.setAccessibilityHeading(view, true); return view;
    }

    public LinearLayout column() {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }

    public GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    public LinearLayout panel() {
        LinearLayout view = column(); view.setPadding(dp(20), dp(20), dp(20), dp(20));
        view.setBackground(shape(surface, border, 22)); return view;
    }

    public MaterialButton button(String label, boolean primary, View.OnClickListener listener) {
        MaterialButton view = new MaterialButton(context);
        view.setText(label); view.setAllCaps(false); view.setTextSize(16); view.setMaxLines(3);
        view.setMinHeight(dp(52)); view.setMinimumHeight(dp(52)); view.setCornerRadius(dp(14));
        view.setInsetTop(0); view.setInsetBottom(0); view.setPadding(dp(18), dp(12), dp(18), dp(12));
        int enabledFill = primary ? accent : surface;
        int enabledText = primary ? onAccent : accent;
        view.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{subtle, enabledFill}));
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{muted, enabledText}));
        if (!primary) { view.setStrokeColor(ColorStateList.valueOf(border)); view.setStrokeWidth(dp(1)); }
        view.setOnClickListener(listener); return view;
    }

    public Drawable icon(String kind, int color) { return new Icon(kind, color, dp(24)); }

    /** Small vector icons, with labels supplied by the hosting native controls. */
    private static final class Icon extends Drawable {
        private final String kind; private final int color, size; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(String kind, int color, int size) { this.kind = kind; this.color = color; this.size = size; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(Paint.Style.STROKE);
            if ("map".equals(kind)) {
                Path p = new Path(); p.moveTo(12, 22); p.cubicTo(9, 18, 4.5f, 13.4f, 4.5f, 9);
                p.cubicTo(4.5f, -0.3f, 19.5f, -0.3f, 19.5f, 9); p.cubicTo(19.5f, 13.4f, 15, 18, 12, 22);
                canvas.drawPath(p, paint); canvas.drawCircle(12, 8.8f, 2.4f, paint);
            } else if ("keyring".equals(kind)) {
                canvas.drawCircle(12, 6, 3.8f, paint); canvas.drawRoundRect(new RectF(5, 9.5f, 19, 22), 5, 5, paint);
                canvas.drawCircle(12, 15.5f, 2.3f, paint);
            } else if ("refresh".equals(kind)) {
                canvas.drawArc(new RectF(4, 4, 20, 20), 40, 280, false, paint);
                Path p = new Path(); p.moveTo(20, 3); p.lineTo(20, 9); p.lineTo(14, 9); canvas.drawPath(p, paint);
            } else if ("settings".equals(kind)) {
                for (int i=0;i<3;i++) { float y=5+i*7; canvas.drawLine(3,y,21,y,paint); }
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(8,5,2.5f,paint); canvas.drawCircle(16,12,2.5f,paint); canvas.drawCircle(9,19,2.5f,paint);
            } else {
                canvas.drawCircle(9, 10, 7, paint); canvas.drawCircle(16, 15, 5.5f, paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
