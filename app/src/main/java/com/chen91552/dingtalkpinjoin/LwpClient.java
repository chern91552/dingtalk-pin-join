package com.chen91552.dingtalkpinjoin;

import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;

import de.robv.android.xposed.XposedHelpers;

final class LwpClient {

    interface Callback {
        void onSuccess(JSONObject response);
        void onFailure(String error);
    }

    private LwpClient() {}

    static void call(String uri, String body, Callback callback) {
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> handlerClass = Class.forName(
                    "com.laiwang.idl.client.RequestHandler", true, cl);
            Object handler = Proxy.newProxyInstance(
                    cl, new Class<?>[]{handlerClass}, new Handler(callback));
            Class<?> callClass = XposedHelpers.findClass(
                    "com.alibaba.lightapp.runtime.plugin.internal.JsapiLwpCall", cl);
            Object call = XposedHelpers.newInstance(
                    callClass, uri, body, new HashMap<String, String>(), handler);
            XposedHelpers.callMethod(call, "execute");
        } catch (Throwable t) {
            callback.onFailure(String.valueOf(t));
        }
    }

    private static final class Handler implements InvocationHandler {
        private final Callback callback;
        private Object filterChain;
        private Object requestParams;
        private Object requestBuilder;
        private StackTraceElement[] callStack;

        Handler(Callback callback) {
            this.callback = callback;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            try {
                switch (name) {
                    case "onSuccess":
                        Object value = args != null && args.length > 0 ? args[0] : null;
                        callback.onSuccess(new JSONObject(String.valueOf(value)));
                        break;
                    case "caught":
                    case "onException":
                    case "onFailure":
                        callback.onFailure(RetryPolicy.describe(args, "LWP 请求失败"));
                        break;
                    case "handleRequest":
                        return Boolean.TRUE;
                    case "onInvoke":
                        if (args != null && args.length > 0 && args[0] instanceof Runnable) {
                            ((Runnable) args[0]).run();
                        }
                        break;
                    case "getType":
                        return String.class;
                    case "getRequestFilterChain":
                        if (filterChain == null) {
                            try {
                                filterChain = XposedHelpers.findClass(
                                        "byh", MainHook.getClassLoader()).newInstance();
                            } catch (Throwable ignored) {}
                        }
                        return filterChain;
                    case "setRequestBuilder":
                        requestBuilder = valueAt(args, 0);
                        break;
                    case "getRequestBuilder":
                        return requestBuilder;
                    case "setRequestParams":
                        requestParams = valueAt(args, 0);
                        break;
                    case "getRequestParams":
                        return requestParams;
                    case "setCallStack":
                        Object stack = valueAt(args, 0);
                        if (stack instanceof StackTraceElement[]) {
                            callStack = (StackTraceElement[]) stack;
                        }
                        break;
                    case "getCallStack":
                        return callStack;
                    case "toString":
                        return "PinJoinLwpHandler";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == valueAt(args, 0);
                    default:
                        break;
                }
            } catch (Throwable t) {
                callback.onFailure(String.valueOf(t));
            }
            return null;
        }

        private static Object valueAt(Object[] args, int index) {
            return args != null && index >= 0 && index < args.length ? args[index] : null;
        }
    }
}
