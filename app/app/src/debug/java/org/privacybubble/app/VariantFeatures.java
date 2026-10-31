package org.privacybubble.app;

import android.app.Activity;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.privacybubble.app.session.MobileSessionStore;
import org.privacybubble.app.ui.AppTheme;

public final class VariantFeatures {
    private VariantFeatures() { }
    public static boolean allowsCustomService() { return true; }

    public static void addSettings(Activity activity, LinearLayout parent, AppTheme theme, MobileSessionStore store, Runnable connected) {
        LinearLayout panel=theme.panel(); panel.addView(theme.heading("개발 도구",19));
        panel.addView(theme.text("개발 빌드에서만 보이는 메뉴예요.",14,theme.muted));
        panel.addView(theme.button("개발 인증 연결",false,v -> connect(activity,theme,store,connected)));
        panel.addView(theme.button("시험 도구 열기",false,v -> activity.startActivity(new Intent(activity,DeveloperToolsActivity.class))));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=theme.dp(18); parent.addView(panel,params);
    }

    private static void connect(Activity activity, AppTheme theme, MobileSessionStore store, Runnable connected) {
        LinearLayout content=theme.column(); content.setPadding(theme.dp(24),theme.dp(12),theme.dp(24),0);
        content.addView(theme.text("개발 서버에서 발급한 임시 인증으로 연결합니다. 먼저 서비스 주소를 저장해 주세요.",15,theme.muted));
        TextInputLayout field=new TextInputLayout(activity); field.setHint("개발 인증 값"); field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input=new TextInputEditText(field.getContext()); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); field.addView(input); content.addView(field);
        new MaterialAlertDialogBuilder(activity).setTitle("개발 연결").setView(content).setNegativeButton("취소",null)
                .setPositiveButton("연결",(d,w) -> {
                    try {
                        if (store.getApiBaseUrl().isEmpty()) throw new IllegalStateException();
                        store.clearSession();
                        store.saveTokens(input.getText()==null?"":input.getText().toString().trim(),"","debug-device-session",System.currentTimeMillis()/1000+1800,"");
                        connected.run();
                    } catch (Exception invalid) { new MaterialAlertDialogBuilder(activity).setTitle("개발 연결 실패").setMessage("서비스 주소와 개발 인증 값을 확인해 주세요.").setPositiveButton("확인",null).show(); }
                }).setOnDismissListener(d -> { if (input.getText()!=null) input.getText().clear(); }).show();
    }
}
