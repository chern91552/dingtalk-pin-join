package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

public class JoinCallbackProxy implements InvocationHandler {

    private final String cid;
    private Object filterChain;
    private Object requestBuilder;

    private JoinCallbackProxy(String cid) {
        this.cid = cid;
    }

    static Object create(ClassLoader cl, String cid) {
        Class<?> iface;
        try {
            iface = Class.forName("com.laiwang.idl.client.RequestHandler", true, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("RequestHandler not found", e);
        }
        return Proxy.newProxyInstance(cl, new Class[]{iface}, new JoinCallbackProxy(cid));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        try {
            switch (name) {
                case "onSuccess":
                    SilentJoin.onJoinOk(cid);
                    break;
                case "caught":
                case "onException":
                case "onFailure":
                    String err = "加群失败";
                    if (args != null && args.length > 0 && args[0] != null) {
                        try {
                            Object resultError = args[0];
                            String reason = (String) XposedHelpers.getObjectField(resultError, "reason");
                            if (reason != null && !reason.isEmpty()) err = reason;
                        } catch (Throwable ignored) {}
                    }
                    SilentJoin.onError(err);
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
