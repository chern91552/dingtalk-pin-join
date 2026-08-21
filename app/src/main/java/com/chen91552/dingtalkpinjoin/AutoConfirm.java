package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.view.View;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自动确认 —— hook JoinGroupConfirmActivity.onCreate，在确认页自动点击"加入"按钮。
 * <p>
 * 门控：只有 JoinLoop.running && armed 同时为 true 才动作（用户手动扫码完全不干扰）。
 * 同时检测"群二维码已过期"页面，发现即停止循环。
 */
public class AutoConfirm extends XC_MethodHook implements Runnable {

    private static final String TAG = "AUTO_CONFIRM";
    private static final int MAX_POLLS = 30;       // 最多轮询 30 次
    private static final long POLL_INTERVAL = 500;  // 每次 500ms（总计 15s）

    public static volatile boolean armed = false;

    private final Activity act;
    private boolean shouldClick;

    public AutoConfirm() {
        this.act = null;
    }

    public AutoConfirm(Activity act) {
        this.act = act;
    }

    // ==================== XC_MethodHook ====================

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        try {
            Activity confirmAct = (Activity) param.thisObject;

            // 仅在循环运行且 armed 时才动作：不启动 worker、不戳 confirmSeenAt，避免循环外
            // 手动打开确认页污染状态机（confirmSeenAt 只在循环内有意义，watchdog 也只在
            // running 时读它）。
            if (JoinLoop.running && armed) {
                JoinLoop.confirmSeenAt = SystemClock.uptimeMillis();
                FileLogger.i(5, TAG, "onCreate captured (ARMED), starting worker");
                new Thread(new AutoConfirm(confirmAct)).start();
            }
        } catch (Exception e) {
            FileLogger.i(5, TAG, "after ERR " + e);
        }
    }

    // ==================== Runnable（后台轮询线程） ====================

    @Override
    public void run() {
        shouldClick = armed;

        if (act == null) {
            FileLogger.i(5, TAG, "worker: act null");
            return;
        }

        for (int i = 0; i < MAX_POLLS; i++) {
            // 早退：确认页已 finish 或 destroy（典型场景：已加过的群，确认页一闪就直接跳进
            // 群聊），不再傻等按钮 15s。这种情况下 onJoined 已由 watchdog 的 cid 变化推进，
            // worker 无需也不能再点任何东西。
            if (act.isFinishing() || act.isDestroyed()) {
                FileLogger.i(5, TAG, "worker: confirm activity gone, exiting early");
                return;
            }

            try {
                // 检测"群二维码已过期"页面
                int errResId = act.getResources().getIdentifier(
                        "tv_verify_error", "id", "com.alibaba.android.rimet");
                if (errResId > 0) {
                    View errView = act.findViewById(errResId);
                    if (errView != null && errView.getVisibility() == View.VISIBLE) {
                        if (JoinLoop.running) {
                            FileLogger.i(5, TAG, "detected expired QR code page; stopping");
                            JoinLoop.stop("群二维码已过期");
                        }
                        return;
                    }
                }

                // 检测确认按钮（v0）
                View btn = (View) XposedHelpers.getObjectField(act, "v0");
                if (btn != null && btn.getVisibility() == View.VISIBLE && btn.isEnabled()) {
                    decideAndClick(btn);
                    return;
                }
            } catch (Exception e) {
                FileLogger.i(5, TAG, "worker ERR " + e);
                return;
            }

            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException ignored) {
                return;
            }
        }

        FileLogger.i(5, TAG, "worker: button not ready after 15s");
    }

    // ==================== 决策并点击 ====================

    private void decideAndClick(View btn) {
        try {
            // 检查是否需要审批
            Object conv = XposedHelpers.getObjectField(act, "c0");
            if (conv != null) {
                Object jvt = XposedHelpers.callMethod(conv, "joinValidationType");
                Class<?> jvtCls = MainHook.getClassLoader().loadClass(
                        "com.alibaba.wukong.im.Conversation$JoinValidationType");
                Object onlyMaster = XposedHelpers.getStaticObjectField(jvtCls, "ONLY_MASTER");

                FileLogger.i(5, TAG, "decide: joinValidationType=" + jvt);

                if (jvt == onlyMaster) {
                    FileLogger.i(5, TAG, "needs approval (ONLY_MASTER); stopping loop");
                    JoinLoop.stop("该群需要管理员审批");
                    return;
                }
            }
        } catch (Exception e) {
            FileLogger.i(5, TAG, "decide: c0 null, click anyway");
        }

        if (!shouldClick) {
            FileLogger.i(5, TAG, "dry-run: would click confirm button now (not armed, skipping)");
            return;
        }

        FileLogger.i(5, TAG, "auto-clicking confirm button");

        // 在主线程执行点击
        btn.post(() -> btn.performClick());

        // 推进状态机
        JoinLoop.advanceFrom(act);
    }
}