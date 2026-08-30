package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 群设置页中的静默加群任务状态栏。
 */
public final class SilentJoinStatusView
        implements SilentJoin.StatusListener, View.OnAttachStateChangeListener {

    private final Activity activity;
    private final LinearLayout root;
    private final View statusDot;
    private final TextView statusText;
    private final Button stopButton;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideResult;

    public SilentJoinStatusView(Activity activity, boolean dark) {
        this.activity = activity;

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(12), dp(7), dp(8), dp(7));
        root.setVisibility(View.GONE);
        root.addOnAttachStateChangeListener(this);
        hideResult = () -> root.setVisibility(View.GONE);

        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? 0xFF252525 : 0xFFF5F7FA);
        background.setCornerRadius(dp(6));
        background.setStroke(dp(1), dark ? 0xFF3A4657 : 0xFFDCE6F5);
        root.setBackground(background);

        statusDot = new View(activity);
        statusDot.setBackground(circle(0xFF1677FF));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(10);
        root.addView(statusDot, dotParams);

        statusText = new TextView(activity);
        statusText.setTextSize(12);
        statusText.setTextColor(dark ? 0xFFE0E0E0 : 0xFF333333);
        statusText.setMaxLines(2);
        root.addView(statusText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stopButton = new Button(activity);
        stopButton.setText("停止");
        stopButton.setTextSize(12);
        stopButton.setTextColor(0xFFE53935);
        stopButton.setAllCaps(false);
        stopButton.setMinWidth(0);
        stopButton.setMinimumWidth(0);
        stopButton.setMinHeight(0);
        stopButton.setMinimumHeight(0);
        stopButton.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable stopBackground = new GradientDrawable();
        stopBackground.setColor(dark ? 0xFF352326 : 0xFFFFF4F4);
        stopBackground.setCornerRadius(dp(4));
        stopBackground.setStroke(dp(1), dark ? 0xFF8E3B3B : 0xFFFFB8B8);
        stopButton.setBackground(stopBackground);
        stopButton.setOnClickListener(v -> SilentJoin.stop());
        root.addView(stopButton);

        SilentJoin.addStatusListener(this);
    }

    public View getView() {
        return root;
    }

    @Override
    public void onStatusChanged(SilentJoin.StatusSnapshot snapshot) {
        if (activity.isFinishing()
                || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
            return;
        }

        mainHandler.removeCallbacks(hideResult);
        if (snapshot.status == SilentJoin.STATUS_IDLE) {
            root.setVisibility(View.GONE);
            return;
        }

        root.setVisibility(View.VISIBLE);
        if (snapshot.status == SilentJoin.STATUS_RUNNING) {
            statusDot.setBackground(circle(0xFF1677FF));
            String text = "静默加群中  " + snapshot.completed + "/" + snapshot.target;
            if (snapshot.groupName != null && !snapshot.groupName.isEmpty()) {
                text += " · 当前：" + snapshot.groupName;
            }
            statusText.setText(text);
            stopButton.setVisibility(View.VISIBLE);
            return;
        }

        int terminalColor = snapshot.status == SilentJoin.STATUS_COMPLETED
                ? 0xFF2E9B57
                : snapshot.status == SilentJoin.STATUS_ERROR ? 0xFFE05252 : 0xFF8A8F98;
        statusDot.setBackground(circle(terminalColor));
        statusText.setText(snapshot.detail);
        stopButton.setVisibility(View.GONE);
        mainHandler.postDelayed(hideResult, Math.max(1L, snapshot.remainingMs));
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        SilentJoin.addStatusListener(this);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        mainHandler.removeCallbacks(hideResult);
        SilentJoin.removeStatusListener(this);
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }
}
