package com.logicrealm.rivune.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

/** Applies safe system-bar and display-cutout spacing on modern Android. */
public final class EdgeToEdge {

    private EdgeToEdge() { }

    public static void apply(Activity activity, View root) {
        if (activity == null || root == null || Build.VERSION.SDK_INT < 30) {
            return;
        }

        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setDecorFitsSystemWindows(false);

        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout()
            );
            view.setPadding(
                    baseLeft + safe.left,
                    baseTop + safe.top,
                    baseRight + safe.right,
                    baseBottom + safe.bottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }
}
