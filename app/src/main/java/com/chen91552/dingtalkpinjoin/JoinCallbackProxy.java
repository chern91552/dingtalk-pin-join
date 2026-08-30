package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

public class JoinCallbackProxy implements InvocationHandler {

    private final String cid;
    private final Object uid;
    private final int origin;
    private final String token;
    private final String code;
    private final long taskId;
    private final int retryCount;
    private Object filterChain;
    private Object requestBuilder;

    private JoinCallbackProxy(String cid, Object uid, int origin, String token, String code,
                              long taskId, int retryCount) {
        this.cid = cid;
        this.uid = uid;
        this.origin = origin;
        this.token = token;
        this.code = code;
        this.taskId = taskId;
        this.retryCount = retryCount;
    }

    static Object create(ClassLoader cl, String cid, Object uid, int origin, String token,
                         String code, long taskId, int retryCount) {
        Class<?> iface;
        try {
            iface = Class.forName("com.laiwang.idl.client.RequestHandler", true, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("RequestHandler not found", e);
        }
        return Proxy.newProxyInstance(cl, new Class[]{iface},
                new JoinCallbackProxy(
                        cid, uid, origin, token, code, taskId, retryCount));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        try {
            switch (name) {
                case "onSuccess":
                    SilentJoin.onJoinOk(cid, taskId);
                    break;
                case "caught":
                case "onException":
                case "onFailure":
                    String error = RetryPolicy.describe(args, "加群失败");
                    if (args != null && args.length > 0 && args[0] != null) {
                        try {
                            Object resultError = args[0];
                            String reason = (String) XposedHelpers.getObjectField(resultError, "reason");
                            if (reason != null && !reason.isEmpty()) error = reason;
                        } catch (Throwable ignored) {}
                    }
                    final String failure = error;
                    if (RetryPolicy.retryIfTransient(taskId, "提交加群", retryCount, failure,
                            () -> SilentJoin.doAddMember(
                                    cid, uid, origin, token, code,
                                    taskId, retryCount + 1))) {
                        break;
                    }
                    SilentJoin.onError(
                            taskId, RetryPolicy.userMessage(failure, "加群失败"));
                    break;
                case "handleRequest":
                    return Boolean.TRUE;
                case "onInvoke":
                    if (args != null && args.length > 0 && args[0] instanceof Runnable) {
                        ((Runnable) args[0]).run();
                    }
                    break;
                case "onCallStart":
                    break;
                case "setRequestBuilder":
                    if (args != null && args.length > 0) requestBuilder = args[0];
                    break;
                case "getRequestBuilder":
                    return requestBuilder;
                case "getRequestFilterChain":
                    if (filterChain == null) {
                        ClassLoader cl = MainHook.getClassLoader();
                        try {
                            Class<?> byh = XposedHelpers.findClass("byh", cl);
                            filterChain = byh.newInstance();
                        } catch (Throwable t) {
                            SilentJoin.log("filterChain ERR: " + t);
                        }
                    }
                    return filterChain;
                case "getType":
                    return null;
                default:
                    break;
            }
        } catch (Throwable t) {
            SilentJoin.log("join proxy ERR [" + name + "]: " + t);
        }
        return null;
    }
}
