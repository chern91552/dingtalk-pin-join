package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

public class VerifyCallbackProxy implements InvocationHandler {

    private final String code;
    private final int origin;
    private final long taskId;
    private final int retryCount;

    private VerifyCallbackProxy(String code, int origin, long taskId, int retryCount) {
        this.code = code;
        this.origin = origin;
        this.taskId = taskId;
        this.retryCount = retryCount;
    }

    static Object create(ClassLoader cl, String code, int origin, long taskId, int retryCount) {
        Class<?> iface;
        try {
            iface = Class.forName("com.alibaba.wukong.Callback", true, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Callback not found", e);
        }
        return Proxy.newProxyInstance(cl, new Class[]{iface},
                new VerifyCallbackProxy(code, origin, taskId, retryCount));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (!SilentJoin.isActive(taskId)) return null;
        try {
            if ("onSuccess".equals(name) && args != null && args.length > 0) {
                Object card = args[0];
                if (card == null) {
                    SilentJoin.onError(taskId, "验证失败");
                    return null;
                }

                Object conv = XposedHelpers.callMethod(card, "getConversation");
                if (conv == null) {
                    SilentJoin.onError(taskId, "验证失败");
                    return null;
                }

                String cid = (String) XposedHelpers.callMethod(conv, "conversationId");
                try {
                    String title = (String) XposedHelpers.callMethod(conv, "title");
                    SilentJoin.setCurrentGroupName(taskId, title);
                } catch (Throwable ignored) {}

                boolean requiresApproval = false;
                try {
                    Object validationType =
                            XposedHelpers.callMethod(conv, "joinValidationType");
                    Class<?> typeClass = XposedHelpers.findClass(
                            "com.alibaba.wukong.im.Conversation$JoinValidationType",
                            MainHook.getClassLoader());
                    Object onlyMaster =
                            XposedHelpers.getStaticObjectField(typeClass, "ONLY_MASTER");
                    if (validationType == onlyMaster || onlyMaster.equals(validationType)) {
                        requiresApproval = true;
                    }
                } catch (Throwable ignored) {}

                Object uid = null;
                try {
                    uid = XposedHelpers.callMethod(card, "getOwnerId");
                } catch (Throwable ignored) {}

                SilentJoin.log("verify OK cid=" + cid + " uid=" + uid
                        + " requiresApproval=" + requiresApproval);
                SilentJoin.joinGroup(
                        cid, uid, origin, code, taskId, requiresApproval);
            } else if ("onFailure".equals(name) || "onException".equals(name)) {
                String error = RetryPolicy.describe(args, "验证失败");
                if (RetryPolicy.retryIfTransient(
                        taskId, "校验邀请码", retryCount, error,
                        () -> SilentJoin.verifyCode(
                                code, String.valueOf(origin), taskId, retryCount + 1))) {
                    return null;
                }
                SilentJoin.onError(
                        taskId, RetryPolicy.userMessage(error, "验证失败"));
            }
        } catch (Throwable t) {
            SilentJoin.log("verify proxy ERR: " + t);
            SilentJoin.onError(taskId, "验证失败");
        }
        return null;
    }
}
