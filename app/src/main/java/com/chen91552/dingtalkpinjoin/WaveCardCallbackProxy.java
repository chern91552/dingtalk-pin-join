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

    private WaveCardCallbackProxy(String cid, ArrayList<WaveCardFetcher.BoxRef> boxes,
                                  long taskId, int retryCount) {
        this.cid = cid;
        this.boxes = boxes;
        this.taskId = taskId;
        this.retryCount = retryCount;
    }

    static Object create(ClassLoader cl, Class<?> callbackCls, String cid,
                         ArrayList<WaveCardFetcher.BoxRef> boxes, long taskId, int retryCount) {
        return Proxy.newProxyInstance(cl, new Class[]{callbackCls},
                new WaveCardCallbackProxy(cid, boxes, taskId, retryCount));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        try {
            if ("onSucceed".equals(name) && args != null && args.length > 0) {
                @SuppressWarnings("unchecked")
                ArrayList<Object> models = (ArrayList<Object>) args[0];
                WaveCardFetcher.onWaveCardResult(models, taskId);
            } else if ("onFailure".equals(name)) {
                WaveCardFetcher.onWaveCardFailure(
                        args != null && args.length > 0 ? args[0] : null,
                        cid, boxes, retryCount, taskId);
            }
        } catch (Throwable t) {
            SilentJoin.log("WaveCard proxy ERR: " + t);
        }
        return null;
    }
}
