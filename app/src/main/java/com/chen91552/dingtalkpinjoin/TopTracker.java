package com.chen91552.dingtalkpinjoin;

import android.app.Activity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 当前群追踪器 —— hook ChatMsgActivity.onResume，
 * 反射读取当前 Conversation 的 cid 和标题，存入全局变量。
 */
public class TopTracker extends XC_MethodHook {

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        try {
            Activity act = (Activity) param.thisObject;
            MainHook.topActivity = act;

            Object conv = XposedHelpers.getObjectField(act, "T0");
            if (conv != null) {
                String cid = (String) XposedHelpers.callMethod(conv, "conversationId");
                String name = (String) XposedHelpers.callMethod(conv, "title");
                MainHook.curCid = cid;
                MainHook.curName = name;
                FileLogger.i(5, "TopTracker", "onResume cid=" + cid + " name=" + name
                        + " act=" + act.getClass().getSimpleName());
            } else {
                FileLogger.i(5, "TopTracker", "onResume fired but T0 null act="
                        + act.getClass().getSimpleName());
            }
        } catch (Exception e) {
            FileLogger.i(5, "TopTracker", "onResume ERR " + e);
        }
    }
}