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
import androidx.core.graphics.ColorUtils;
import com.google.android.material.button.MaterialButton;
import org.privacybubble.app.R;

public final class AppTheme {
    public final Context context;
    public final int background, surface, subtle, text, muted, accent, onAccent, border, warning, danger, success;
    public final int accentText, accentPressed, accentSoft, controlBorder;
    public final int chrome, onChrome, mutedOnChrome, accentOnChrome, warningOnChrome;

    public AppTheme(Context context) {
        this.context = context;
        background = context.getColor(R.color.pb_background); surface = context.getColor(R.color.pb_surface);
        subtle = context.getColor(R.color.pb_subtle); text = context.getColor(R.color.pb_text);
        muted = context.getColor(R.color.pb_muted); accent = context.getColor(R.color.pb_accent);
        onAccent = context.getColor(R.color.pb_on_accent); border = context.getColor(R.color.pb_border);
        warning = context.getColor(R.color.pb_warning); danger = context.getColor(R.color.pb_danger);
        success = context.getColor(R.color.pb_success);
        accentText = context.getColor(R.color.pb_accent_text); accentPressed = context.getColor(R.color.pb_accent_pressed);
        accentSoft = context.getColor(R.color.pb_accent_soft); controlBorder = context.getColor(R.color.pb_control_border);
        chrome = context.getColor(R.color.pb_chrome); onChrome = context.getColor(R.color.pb_on_chrome);
        mutedOnChrome = context.getColor(R.color.pb_muted_on_chrome); accentOnChrome = context.getColor(R.color.pb_accent_on_chrome);
        warningOnChrome = context.getColor(R.color.pb_warning_on_chrome);
    }

    public int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    public TextView text(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setLineSpacing(dp(2), 1.16f);
        view.setLetterSpacing(size >= 17 ? -0.01f : 0);
        view.setIncludeFontPadding(true);
        return view;
    }

    public TextView heading(String value, int size) {
        TextView view = text(value, size, text); view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLetterSpacing(size >= 24 ? -0.02f : -0.01f);
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
        view.setBackground(shape(surface, border, 18)); return view;
    }

    public MaterialButton button(String label, boolean primary, View.OnClickListener listener) {
        MaterialButton view = new MaterialButton(context);
        view.setText(label); view.setAllCaps(false); view.setTextSize(16); view.setMaxLines(3);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); view.setLetterSpacing(0);
        view.setMinHeight(dp(52)); view.setMinimumHeight(dp(52)); view.setMinWidth(dp(48)); view.setMinimumWidth(dp(48));
        view.setCornerRadius(dp(999)); view.setElevation(0); view.setStateListAnimator(null);
        view.setInsetTop(0); view.setInsetBottom(0); view.setPadding(dp(18), dp(12), dp(18), dp(12));
        int enabledText = primary ? onAccent : accentText;
        view.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_pressed}, new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{subtle, primary ? accentPressed : subtle, primary ? accent : subtle, primary ? accent : Color.TRANSPARENT}));
        ColorStateList foreground = new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{muted, enabledText});
        view.setTextColor(foreground); view.setIconTint(foreground);
        view.setRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary ? onAccent : accentText, 35)));
        if (!primary) { view.setStrokeColor(ColorStateList.valueOf(controlBorder)); view.setStrokeWidth(dp(1)); }
        view.setOnClickListener(listener); return view;
    }

    public Drawable icon(String kind, int color) { return new Icon(kind, color, dp(24)); }

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
                canvas.drawCircle(15, 12, 5, paint); canvas.drawLine(10,12,2,12,paint);
                canvas.drawLine(5,12,5,16,paint); canvas.drawLine(8,12,8,14,paint);
            } else if ("refresh".equals(kind)) {
                canvas.drawArc(new RectF(4, 4, 20, 20), 40, 280, false, paint);
                Path p = new Path(); p.moveTo(20, 3); p.lineTo(20, 9); p.lineTo(14, 9); canvas.drawPath(p, paint);
            } else if ("settings".equals(kind)) {
                for (int i=0;i<3;i++) { float y=5+i*7; canvas.drawLine(3,y,21,y,paint); }
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(8,5,2.5f,paint); canvas.drawCircle(16,12,2.5f,paint); canvas.drawCircle(9,19,2.5f,paint);
            } else if ("back".equals(kind)) {
                Path p = new Path(); p.moveTo(15,5); p.lineTo(8,12); p.lineTo(15,19); canvas.drawPath(p,paint);
            } else if ("more".equals(kind)) {
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12,5,1.5f,paint); canvas.drawCircle(12,12,1.5f,paint); canvas.drawCircle(12,19,1.5f,paint);
            } else if ("close".equals(kind)) {
                canvas.drawLine(5,5,19,19,paint); canvas.drawLine(19,5,5,19,paint);
            } else if ("home".equals(kind)) {
                Path p = new Path(); p.moveTo(3,11.5f); p.lineTo(12,4); p.lineTo(21,11.5f);
                p.moveTo(5.5f,9.5f); p.lineTo(5.5f,20); p.lineTo(10,20); p.lineTo(10,14);
                p.lineTo(14,14); p.lineTo(14,20); p.lineTo(18.5f,20); p.lineTo(18.5f,9.5f); canvas.drawPath(p,paint);
            } else {
                Path p = new Path(); p.moveTo(12,3); p.cubicTo(7,3,3,7,3,12);
                p.cubicTo(3,13.8f,3.5f,15.5f,4.4f,16.8f); p.lineTo(3,21); p.lineTo(7.3f,19.6f);
                p.cubicTo(8.7f,20.5f,10.3f,21,12,21); p.cubicTo(17,21,21,17,21,12);
                p.cubicTo(21,7,17,3,12,3); p.close(); canvas.drawPath(p,paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
