package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/**
 * WaveCard SDK fetchCardInfoList 的回调代理。
 * onSucceed(ArrayList<WaveCardModel>) → 解析 nexturl
 * onFailure(NestError) → 报错停止
 */
public class WaveCardCallbackProxy implements InvocationHandler {

    private final long taskId;
    private final String cid;
    private final ArrayList<WaveCardFetcher.BoxRef> boxes;
    private final int retryCount;
    private final RpcWatchdog.Token watchdog;

    private WaveCardCallbackProxy(String cid, ArrayList<WaveCardFetcher.BoxRef> boxes,
                                  long taskId, int retryCount, RpcWatchdog.Token watchdog) {
        this.cid = cid;
        this.boxes = boxes;
        this.taskId = taskId;
        this.retryCount = retryCount;
        this.watchdog = watchdog;
    }

    static Object create(ClassLoader cl, Class<?> callbackCls, String cid,
                         ArrayList<WaveCardFetcher.BoxRef> boxes, long taskId, int retryCount,
                         RpcWatchdog.Token watchdog) {
        return Proxy.newProxyInstance(cl, new Class[]{callbackCls},
                new WaveCardCallbackProxy(cid, boxes, taskId, retryCount, watchdog));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        try {
            if ("onSucceed".equals(name) && args != null && args.length > 0) {
                if (!watchdog.claim()) return null;
                @SuppressWarnings("unchecked")
                ArrayList<Object> models = (ArrayList<Object>) args[0];
                WaveCardFetcher.onWaveCardResult(models, taskId);
            } else if ("onFailure".equals(name)) {
                if (!watchdog.claim()) return null;
                WaveCardFetcher.onWaveCardFailure(
                        args != null && args.length > 0 ? args[0] : null,
                        cid, boxes, retryCount, taskId);
            }
        } catch (Throwable t) {
            SilentJoin.log("WaveCard proxy ERR: " + t);
            if ("onSucceed".equals(name) || "onFailure".equals(name)) {
                WaveCardFetcher.onWaveCardFailure(
                        t, cid, boxes, retryCount, taskId);
            }
        }
        return null;
    }
}
