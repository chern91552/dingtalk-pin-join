package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import de.robv.android.xposed.XposedHelpers;

final class ForestAuditNextFetcher {

    interface Callback {
        void onLink(String link);
        void onFailure(String error);
    }

    private ForestAuditNextFetcher() {}

    static void resolve(String cid, long taskId, Callback callback) {
        if (!ForestAudit.isActive(taskId)) return;
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> apiClass = Class.forName("q8i", true, cl);
            Object api = XposedHelpers.callStaticMethod(apiClass, "u");
            api = XposedHelpers.callMethod(api, "d");

            Class<?> requestClass = Class.forName(
                    "com.alibaba.android.dingtalkim.chatcontext.idl.ChatContextRequestModel",
                    true, cl);
            Object request = requestClass.newInstance();
            XposedHelpers.setObjectField(request, "type", "TOP_INTERACTION");
            ArrayList<Object> requests = new ArrayList<>();
            requests.add(request);

            Class<?> listenerClass = Class.forName(
                    "com.alibaba.android.dingtalkbase.rpc.ApiEventListener", true, cl);
            Object listener = Proxy.newProxyInstance(
                    cl, new Class<?>[]{listenerClass},
                    new ChatContextHandler(cid, taskId, callback));
            XposedHelpers.callMethod(
                    api, "getChatContextByTypes", cid, requests, listener);
        } catch (Throwable t) {
            callback.onFailure("读取置顶失败：" + t);
        }
    }

    private static final class ChatContextHandler implements InvocationHandler {
        private final String cid;
        private final long taskId;
        private final Callback callback;

        ChatContextHandler(String cid, long taskId, Callback callback) {
            this.cid = cid;
            this.taskId = taskId;
            this.callback = callback;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (!ForestAudit.isActive(taskId)) return null;
            try {
                String name = method.getName();
                if ("onDataReceived".equals(name)) {
                    Object result = args != null && args.length > 0 ? args[0] : null;
                    fetchCards(cid, parseBoxes(result), taskId, callback);
                } else if ("onException".equals(name) || "onFailure".equals(name)) {
                    callback.onFailure(
                            RetryPolicy.describe(args, "读取置顶失败"));
                }
            } catch (Throwable t) {
                callback.onFailure("解析置顶失败：" + t);
            }
            return null;
        }
    }

    private static ArrayList<WaveCardFetcher.BoxRef> parseBoxes(Object result) {
        ArrayList<WaveCardFetcher.BoxRef> boxes = new ArrayList<>();
        if (!(result instanceof Collection)) return boxes;
        for (Object context : (Collection<?>) result) {
            Object top;
            try {
                top = XposedHelpers.getObjectField(context, "topInteractionObject");
            } catch (Throwable ignored) {
                continue;
            }
            if (top == null) continue;
            Object rawBoxes;
            try {
                rawBoxes = XposedHelpers.getObjectField(top, "boxObjectList");
            } catch (Throwable ignored) {
                continue;
            }
            if (!(rawBoxes instanceof Collection)) continue;
            for (Object box : (Collection<?>) rawBoxes) {
                try {
                    Object data = XposedHelpers.getObjectField(box, "boxData");
                    if (data == null) continue;
                    String boxId = String.valueOf(
                            XposedHelpers.getObjectField(box, "boxId"));
                    String platform = objectString(data, "distributePlatform");
                    long msgId = objectLong(data, "msgId", 0L);
                    long cardId = objectLong(data, "cardInstanceId", 0L);
                    if (cardId <= 0L) cardId = objectLong(data, "id", 0L);
                    if (cardId <= 0L && boxId != null) cardId = 1L;
                    if (cardId > 0L) {
                        boxes.add(new WaveCardFetcher.BoxRef(
                                cardId, boxId, platform, msgId));
                    }
                } catch (Throwable ignored) {}
            }
        }
        return boxes;
    }

    private static void fetchCards(String cid, ArrayList<WaveCardFetcher.BoxRef> boxes,
                                   long taskId, Callback callback) {
        if (!ForestAudit.isActive(taskId)) return;
        if (boxes.isEmpty()) {
            callback.onFailure("该群没有置顶卡片");
            return;
        }
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> requestFactory = Class.forName("nf2", true, cl);
            Class<?> waveHelper = Class.forName("rwm", true, cl);
            ArrayList<Object> parameters = new ArrayList<>();
            for (WaveCardFetcher.BoxRef box : boxes) {
                Object request = XposedHelpers.callStaticMethod(
                        requestFactory, "e", box.cardInstanceId, cid, box.boxId,
                        box.distributePlatform, String.valueOf(box.msgId));
                Object parameter = XposedHelpers.callStaticMethod(waveHelper, "k", request);
                try {
                    @SuppressWarnings("unchecked")
                    HashMap<String, String> ext = (HashMap<String, String>)
                            XposedHelpers.getObjectField(parameter, "ext");
                    if (ext != null) ext.put("formatJSDisabled", "1");
                } catch (Throwable ignored) {}
                parameters.add(parameter);
            }

            Class<?> managerClass = Class.forName(
                    "com.alibaba.android.dingtalk.wave.WaveCardSDKManager", true, cl);
            Object sdk = XposedHelpers.callStaticMethod(managerClass, "q");
            Object service = XposedHelpers.callMethod(sdk, "r");
            Class<?> callbackClass = Class.forName(
                    "com.dingtalk.nest.wave_card.WaveCardModelListCallBack", true, cl);
            Object waveCallback = Proxy.newProxyInstance(
                    cl, new Class<?>[]{callbackClass},
                    new WaveHandler(taskId, callback));
            XposedHelpers.callStaticMethod(
                    waveHelper, "q", service, parameters, waveCallback);
        } catch (Throwable t) {
            callback.onFailure("读取置顶卡片失败：" + t);
        }
    }

    private static final class WaveHandler implements InvocationHandler {
        private final long taskId;
        private final Callback callback;

        WaveHandler(long taskId, Callback callback) {
            this.taskId = taskId;
            this.callback = callback;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (!ForestAudit.isActive(taskId)) return null;
            String name = method.getName();
            if ("onSucceed".equals(name)) {
                Object value = args != null && args.length > 0 ? args[0] : null;
                if (!(value instanceof Collection)) {
                    callback.onFailure("置顶卡片数据为空");
                    return null;
                }
                for (Object model : (Collection<?>) value) {
                    try {
                        @SuppressWarnings("unchecked")
                        HashMap<String, Object> cardViewData =
                                (HashMap<String, Object>) XposedHelpers.getObjectField(
                                        model, "cardViewData");
                        Object cardData = cardViewData == null
                                ? null : cardViewData.get("cardData");
                        String link = cardData instanceof String
                                ? WaveCardFetcher.pickLink((String) cardData) : null;
                        if (link != null) {
                            callback.onLink(link);
                            return null;
                        }
                    } catch (Throwable ignored) {}
                }
                callback.onFailure("卡片中没有下一群链接");
            } else if ("onFailure".equals(name)) {
                callback.onFailure(
                        RetryPolicy.describe(args, "读取置顶卡片失败"));
            }
            return null;
        }
    }

    private static String objectString(Object target, String field) {
        try {
            Object value = XposedHelpers.getObjectField(target, field);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long objectLong(Object target, String field, long fallback) {
        try {
            Object value = XposedHelpers.getObjectField(target, field);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value != null) return Long.parseLong(String.valueOf(value));
        } catch (Throwable ignored) {}
        return fallback;
    }
}
