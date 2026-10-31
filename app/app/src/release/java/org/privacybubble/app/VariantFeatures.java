package org.privacybubble.app;

import android.app.Activity;
import android.widget.LinearLayout;
import org.privacybubble.app.session.MobileSessionStore;
import org.privacybubble.app.ui.AppTheme;

public final class VariantFeatures {
    private VariantFeatures() { }
    public static boolean allowsCustomService() { return false; }
    public static void addSettings(Activity activity, LinearLayout parent, AppTheme theme, MobileSessionStore store, Runnable connected) { }
}
