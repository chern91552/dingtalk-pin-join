package com.chen91552.dingtalkpinjoin;

import android.app.Activity;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 群设置页 onResume 回调 —— 命名类（非匿名类），确保 hook 正常触发
 */
public class SettingsResumeHook extends XC_MethodHook {

    private final MainHook mainHook;

    public SettingsResumeHook(MainHook mainHook) {
        this.mainHook = mainHook;
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        try {
            Activity act = (Activity) param.thisObject;
            android.util.Log.i("PinJoin", "SettingsResumeHook fired, act=" + act.getClass().getName());
            mainHook.injectInto(act);
        } catch (Throwable e) {
            android.util.Log.e("PinJoin", "SettingsResumeHook ERR: " + e.getMessage());
        }
    }
}