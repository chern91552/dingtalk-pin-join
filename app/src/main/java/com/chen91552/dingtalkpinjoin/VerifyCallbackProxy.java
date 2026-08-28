package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

public class VerifyCallbackProxy implements InvocationHandler {

    private final String code;
    private final int origin;

    private VerifyCallbackProxy(String code, int origin) {
        this.code = code;
        this.origin = origin;
    }

    static Object create(ClassLoader cl, String code, int origin) {
        Class<?> iface;
        try {
            iface = Class.forName("com.alibaba.wukong.Callback", true, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Callback not found", e);
        }
        return Proxy.newProxyInstance(cl, new Class[]{iface},
                new VerifyCallbackProxy(code, origin));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        try {
            if ("onSuccess".equals(name) && args != null && args.length > 0) {
                Object card = args[0];
                if (card == null) {
                    SilentJoin.onError("验证失败");
                    return null;
                }

                Object conv = XposedHelpers.callMethod(card, "getConversation");
                if (conv == null) {
                    SilentJoin.onError("验证失败");
                    return null;
                }

                String cid = (String) XposedHelpers.callMethod(conv, "conversationId");
                try {
                    String title = (String) XposedHelpers.callMethod(conv, "title");
                    if (title != null) SilentJoin.lastGroupName = title;
                } catch (Throwable ignored) {}

                Object uid = null;
                try {
                    uid = XposedHelpers.callMethod(card, "getOwnerId");
                } catch (Throwable ignored) {}

                SilentJoin.log("verify OK cid=" + cid + " uid=" + uid);
                SilentJoin.joinGroup(cid, uid, origin, code);
            } else if ("onFailure".equals(name) || "onException".equals(name)) {
                String err = "验证失败";
                if (args != null && args.length >= 2 && args[1] != null) {
                    String s = args[1].toString();
                    if (s.contains("400007")) err = "群二维码已过期";
                    else err = s;
                }
                SilentJoin.onError(err);
            }
        } catch (Throwable t) {
            SilentJoin.log("verify proxy ERR: " + t);
            SilentJoin.onError("验证失败");
        }
        return null;
    }
}
