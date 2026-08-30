package com.chen91552.dingtalkpinjoin;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Hook rwm.h(WaveCardModel, dg2):JSONObject，在卡片自然渲染时捕获 nexturl。
 * 仅当 SilentJoin.running 时才处理，避免普通浏览时自动加群。
 */
public class DurboProbe extends XC_MethodHook {

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        long taskId = SilentJoin.getActiveTaskId();
        if (!SilentJoin.isActive(taskId)) return;
        try {
            Object result = param.getResult();
            if (result == null) return;

            // result.get("cardData")
            Object cardData = de.robv.android.xposed.XposedHelpers.callMethod(
                    result, "get", "cardData");
            if (!(cardData instanceof String)) return;

            String cardDataStr = (String) cardData;
            String key = "\"nexturl\":\"";
            int start = cardDataStr.indexOf(key);
            if (start < 0) return;
            start += key.length();
            int end = cardDataStr.indexOf("\"", start);
            if (end < 0) return;

            String nexturl = cardDataStr.substring(start, end);
            SilentJoin.onNextUrl(nexturl, taskId);
        } catch (Throwable ignored) {}
    }
}
