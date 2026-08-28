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

    private SafeCheckCallbackProxy(String cid, Object uid, int origin, String code) {
        this.cid = cid;
        this.uid = uid;
        this.origin = origin;
        this.code = code;
    }

    static Object create(ClassLoader cl, String cid, Object uid, int origin, String code) {
        Class<?> iface;
        try {
            iface = Class.forName("com.alibaba.wukong.Callback", true, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Callback not found", e);
        }
        return Proxy.newProxyInstance(cl, new Class[]{iface},
                new SafeCheckCallbackProxy(cid, uid, origin, code));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
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
                    SilentJoin.doAddMember(cid, uid, origin, token, code);
                } else {
                    SilentJoin.onError("安全检查失败");
                }
            } else if ("onFailure".equals(name) || "onException".equals(name)) {
                SilentJoin.onError("安全检查失败");
            }
        } catch (Throwable t) {
            SilentJoin.log("safeCheck proxy ERR: " + t);
            SilentJoin.onError("安全检查失败");
        }
        return null;
    }
}
