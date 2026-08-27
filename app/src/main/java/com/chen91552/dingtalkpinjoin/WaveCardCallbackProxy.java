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

    static Object create(ClassLoader cl, Class<?> callbackCls) {
        return Proxy.newProxyInstance(cl, new Class[]{callbackCls},
                new WaveCardCallbackProxy());
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        try {
            if ("onSucceed".equals(name) && args != null && args.length > 0) {
                @SuppressWarnings("unchecked")
                ArrayList<Object> models = (ArrayList<Object>) args[0];
                WaveCardFetcher.onWaveCardResult(models);
            } else if ("onFailure".equals(name)) {
                WaveCardFetcher.onWaveCardFailure(args != null && args.length > 0 ? args[0] : null);
            }
        } catch (Throwable t) {
            SilentJoin.log("WaveCard proxy ERR: " + t);
        }
        return null;
    }
}
