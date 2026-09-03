package com.chen91552.dingtalkpinjoin;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 静默加群 RPC 的保守重试策略：只重试明确的临时错误。
 */
final class RetryPolicy {

    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_DELAYS_MS = {1000L, 2000L};

    private RetryPolicy() {}

    static boolean retryIfTransient(long taskId, String stage, int retryCount,
                                    String error, Runnable retryAction) {
        if (!SilentJoin.isActive(taskId)
                || retryCount >= MAX_RETRIES
                || !isTransient(error)) {
            return false;
        }

        long delay = RETRY_DELAYS_MS[retryCount];
        int nextRetry = retryCount + 1;
        SilentJoin.log("[RETRY] " + stage + "失败：" + error
                + "；" + delay + "ms 后重试 " + nextRetry + "/" + MAX_RETRIES);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (SilentJoin.isActive(taskId)) retryAction.run();
        }, delay);
        return true;
    }

    static String describe(Object[] args, String fallback) {
        if (args == null || args.length == 0) return fallback;
        StringBuilder result = new StringBuilder();
        for (Object arg : args) {
            if (arg == null) continue;
            String value = describeObject(arg);
            if (value.isEmpty()) continue;
            if (result.length() > 0) result.append(" | ");
            result.append(value);
        }
        return result.length() == 0 ? fallback : result.toString();
    }

    static String userMessage(String error, String fallback) {
        String normalized = normalize(error);
        if (normalized.contains("400007")
                || normalized.contains("二维码已过期")
                || normalized.contains("邀请码已过期")
                || normalized.contains("expired")) {
            return "群二维码已过期";
        }
        if (normalized.contains("only_master")
                || normalized.contains("需要审批")
                || normalized.contains("管理员审批")
                || normalized.contains("入群申请")
                || normalized.contains("申请入群")
                || (normalized.contains("群主") && normalized.contains("邀请"))) {
            return "该群需要管理员审批";
        }
        return error == null || error.trim().isEmpty() ? fallback : error;
    }

    private static boolean isTransient(String error) {
        String normalized = normalize(error);
        if (normalized.isEmpty() || isPermanent(normalized)) return false;

        return normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("time out")
                || normalized.contains("超时")
                || normalized.contains("网络")
                || normalized.contains("network")
                || normalized.contains("系统繁忙")
                || normalized.contains("server busy")
                || normalized.contains("service unavailable")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("try again")
                || normalized.contains("稍后重试")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused")
                || normalized.contains("unable to resolve host")
                || normalized.contains("socket");
    }

    private static boolean isPermanent(String normalized) {
        return normalized.contains("400007")
                || normalized.contains("过期")
                || normalized.contains("失效")
                || normalized.contains("expired")
                || normalized.contains("only_master")
                || normalized.contains("需要审批")
                || normalized.contains("管理员审批")
                || normalized.contains("入群申请")
                || normalized.contains("申请入群")
                || (normalized.contains("群主") && normalized.contains("邀请"));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String describeObject(Object value) {
        if (value instanceof CharSequence
                || value instanceof Number
                || value instanceof Throwable) {
            return String.valueOf(value);
        }

        StringBuilder detail = new StringBuilder(String.valueOf(value));
        String[] names = {"code", "errorCode", "reason", "message", "errorMsg"};
        for (String name : names) {
            Object fieldValue = readField(value, name);
            if (fieldValue == null) fieldValue = callGetter(value, name);
            if (fieldValue != null) {
                detail.append(' ').append(name).append('=').append(fieldValue);
            }
        }
        return detail.toString();
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object callGetter(Object target, String name) {
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Method method = target.getClass().getMethod(getter);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
