package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import de.robv.android.xposed.XposedHelpers;

/**
 * 全局 Activity 生命周期跟踪器（对齐能用版的 q2）。
 * <p>
 * 在每次任意 Activity resume 时更新 MainHook.topActivity。这是比 TopTracker
 * （hook ChatMsgActivity.onResume）更可靠的前台 Activity 追踪：不依赖目标类是否
 * 自己声明了 onResume（hookAllMethods 对继承来的方法返回空集），框架会对每个
 * Activity 回调 onActivityResumed。
 * <p>
 * 加群循环依赖 topActivity 指向"刚跳入的新群聊天页"来点下一张卡片；如果这个引用
 * 停留在旧群/确认页，就会在旧群上重复点同一张未刷新的卡片，导致重复加群。
 */
public class ActivityTracker implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onActivityResumed(Activity activity) {
        MainHook.topActivity = activity;

        // 若是聊天页，顺便刷新 curCid/curName（覆盖 TopTracker hook 未触发的情况）
        try {
            String name = activity.getClass().getName();
            if (name.contains("ChatMsgActivity")) {
                Object conv = XposedHelpers.getObjectField(activity, "T0");
                if (conv != null) {
                    Object cid = XposedHelpers.callMethod(conv, "conversationId");
                    if (cid != null) {
                        MainHook.curCid = (String) cid;
                        MainHook.curName = (String) XposedHelpers.callMethod(conv, "title");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
