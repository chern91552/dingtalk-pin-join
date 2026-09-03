package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Applies notification preferences after a newly joined conversation enters memory.
 */
public final class GroupNotificationSettings {

    private static final String PREFIX = "[NOTIFY_SETTING] ";
    private static final int MAX_ATTEMPTS = 12;
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_SETTING_RETRIES = 2;
    private static final Set<Long> CANCELLED_TASKS =
            ConcurrentHashMap.newKeySet();

    private GroupNotificationSettings() {}

    public static void apply(String cid, boolean disableAtAll, long taskId) {
        new Thread(
                () -> run(cid, disableAtAll, taskId),
                "notify-setting-" + cid).start();
    }

    public static void cancel(long taskId) {
        CANCELLED_TASKS.add(taskId);
    }

    private static void run(String cid, boolean disableAtAll, long taskId) {
        Object conversation = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (isCancelled(taskId)) return;
            conversation = getConversation(cid);
            if (conversation != null) break;
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (isCancelled(taskId)) return;
        if (conversation == null) {
            reportFailure(cid, "会话尚未就绪");
            return;
        }

        final Object readyConversation = conversation;
        invokeSetting(
                readyConversation, "updateNotification", false, "消息免打扰",
                cid, taskId, 0,
                disableAtAll
                        ? () -> schedule(() -> invokeSetting(
                                readyConversation, "updateAtAllNotification", false,
                                "@所有人不提醒", cid, taskId, 0, null),
                                RETRY_DELAY_MS, taskId)
                        : null);
    }

    private static Object getConversation(String cid) {
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> serviceClass = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.ConversationService", cl);
            Class<?> engineClass = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.IMEngine", cl);
            Object service = XposedHelpers.callStaticMethod(
                    engineClass, "getIMService", serviceClass);
            return XposedHelpers.callMethod(service, "getConversationFromMemory", cid);
        } catch (Throwable t) {
            log("get conversation failed cid=" + cid + ": " + t);
            return null;
        }
    }

    private static void invokeSetting(Object conversation, String methodName, boolean enabled,
                                      String label, String cid, long taskId, int retryCount,
                                      Runnable onComplete) {
        if (isCancelled(taskId)) return;
        try {
            Object callback = createCallback(
                    conversation, methodName, enabled, label, cid,
                    taskId, retryCount, onComplete);
            XposedHelpers.callMethod(conversation, methodName, enabled, callback);
            log(label + " request sent cid=" + cid + " retry=" + retryCount);
        } catch (Throwable t) {
            handleFailure(
                    conversation, methodName, enabled, label, cid, taskId, retryCount,
                    shortError(t), onComplete);
        }
    }

    private static Object createCallback(Object conversation, String methodName, boolean enabled,
                                         String label, String cid, long taskId, int retryCount,
                                         Runnable onComplete) throws ClassNotFoundException {
        ClassLoader cl = MainHook.getClassLoader();
        Class<?> callbackClass = Class.forName(
                "com.alibaba.wukong.Callback", true, cl);
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (isCancelled(taskId)) return null;
                switch (method.getName()) {
                    case "onSuccess":
                        SilentJoin.summary("[群设置] " + label + "已开启："
                                + groupName(cid));
                        log(label + " success cid=" + cid);
                        runCompletion(onComplete);
                        break;
                    case "onException":
                        String code = valueAt(args, 0);
                        String reason = valueAt(args, 1);
                        handleFailure(
                                conversation, methodName, enabled, label, cid,
                                taskId, retryCount,
                                (code + " " + reason).trim(), onComplete);
                        break;
                    case "toString":
                        return "PinJoinNotificationCallback(" + label + ")";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == valueObjectAt(args, 0);
                    default:
                        break;
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(cl, new Class<?>[]{callbackClass}, handler);
    }

    private static void handleFailure(Object conversation, String methodName, boolean enabled,
                                      String label, String cid, long taskId, int retryCount,
                                      String error, Runnable onComplete) {
        if (isCancelled(taskId)) return;
        if (isTransient(error) && retryCount < MAX_SETTING_RETRIES) {
            long delay = (retryCount + 1L) * 1000L;
            log(label + " transient failure, retry " + (retryCount + 1)
                    + "/" + MAX_SETTING_RETRIES + " after " + delay + "ms: " + error);
            schedule(() -> invokeSetting(
                    conversation, methodName, enabled, label, cid, taskId,
                    retryCount + 1, onComplete), delay, taskId);
            return;
        }
        reportFailure(cid, label + "设置失败：" + error);
        runCompletion(onComplete);
    }

    private static boolean isTransient(String error) {
        String text = error == null ? "" : error.toLowerCase();
        return text.contains("1002")
                || text.contains("系统繁忙")
                || text.contains("timeout")
                || text.contains("timed out")
                || text.contains("network")
                || text.contains("网络");
    }

    private static void schedule(Runnable action, long delayMs, long taskId) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                if (!isCancelled(taskId)) action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "notify-setting-delay").start();
    }

    private static boolean isCancelled(long taskId) {
        return CANCELLED_TASKS.contains(taskId);
    }

    private static void runCompletion(Runnable onComplete) {
        if (onComplete != null) onComplete.run();
    }

    private static String groupName(String cid) {
        try {
            Object conversation = getConversation(cid);
            if (conversation != null) {
                Object title = XposedHelpers.callMethod(conversation, "title");
                if (title instanceof String && !((String) title).isEmpty()) {
                    return (String) title;
                }
            }
        } catch (Throwable ignored) {}
        return "cid=" + cid;
    }

    private static void reportFailure(String cid, String message) {
        SilentJoin.summary("[群设置失败] " + groupName(cid) + "：" + message);
        log(message + " cid=" + cid);
    }

    private static String valueAt(Object[] args, int index) {
        Object value = valueObjectAt(args, index);
        return value == null ? "" : String.valueOf(value);
    }

    private static Object valueObjectAt(Object[] args, int index) {
        return args != null && index >= 0 && index < args.length ? args[index] : null;
    }

    private static String shortError(Throwable error) {
        String text = String.valueOf(error);
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static void log(String message) {
        try {
            FileLogger.write(FileLogger.CAT_SYSTEM, PREFIX + message);
        } catch (Throwable ignored) {}
    }
}
