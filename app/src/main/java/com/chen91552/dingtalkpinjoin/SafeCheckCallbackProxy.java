package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

public class SafeCheckCallbackProxy implements InvocationHandler {

    private final String cid;
    private final Object uid;
    private final int origin;
    private final String code;
    private final long taskId;
    private final int retryCount;
    private final RpcWatchdog.Token watchdog;

    private SafeCheckCallbackProxy(String cid, Object uid, int origin, String code,
                                   long taskId, int retryCount, RpcWatchdog.Token watchdog) {
        this.cid = cid;
        this.uid = uid;
        this.origin = origin;
        this.code = code;
        this.taskId = taskId;
        this.retryCount = retryCount;
        this.watchdog = watchdog;
    }

    static Object create(ClassLoader cl, String cid, Object uid, int origin, String code,
                         long taskId, int retryCount, RpcWatchdog.Token watchdog) {
        Class<?> iface;
        try {
            iface = Class.forName("com.alibaba.wukong.Callback", true, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Callback not found", e);
        }
        return Proxy.newProxyInstance(cl, new Class[]{iface},
                new SafeCheckCallbackProxy(
                        cid, uid, origin, code, taskId, retryCount, watchdog));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (isTerminal(name) && !watchdog.claim()) return null;
        if (!SilentJoin.isActive(taskId)) return null;
        try {
            if ("onSuccess".equals(name) && args != null && args.length > 0) {
                Object result = args[0];
                String token = null;
                try {
                    token = (String) XposedHelpers.getObjectField(result, "token");
                } catch (Throwable ignored) {
                    try {
                        token = (String) XposedHelpers.callMethod(result, "getToken");
                    } catch (Throwable ignored2) {}
                }
                if (token != null && !token.isEmpty()) {
                    SilentJoin.doAddMember(cid, uid, origin, token, code, taskId);
                } else {
                    String error = RetryPolicy.describe(
                            new Object[]{result}, "安全检查未返回 token");
                    SilentJoin.onError(
                            taskId, RetryPolicy.userMessage(error, "安全检查失败"));
                }
            } else if ("onFailure".equals(name) || "onException".equals(name)) {
                String error = RetryPolicy.describe(args, "安全检查失败");
                if (RetryPolicy.retryIfTransient(taskId, "安全检查", retryCount, error,
                        () -> SilentJoin.requestSafeCheck(
                                cid, uid, origin, code, taskId, retryCount + 1))) {
                    return null;
                }
                SilentJoin.onError(
                        taskId, RetryPolicy.userMessage(error, "安全检查失败"));
            }
        } catch (Throwable t) {
            SilentJoin.log("safeCheck proxy ERR: " + t);
            SilentJoin.onError(taskId, "安全检查失败");
        }
        return null;
    }

    private static boolean isTerminal(String name) {
        return "onSuccess".equals(name)
                || "onFailure".equals(name)
                || "onException".equals(name);
    }
}
