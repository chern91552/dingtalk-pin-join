package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;

import de.robv.android.xposed.XposedHelpers;

public class ChatContextListenerProxy implements InvocationHandler {

    private final String cid;
    private final int attempt;
    private final long taskId;
    private final RpcWatchdog.Token watchdog;

    private ChatContextListenerProxy(String cid, int attempt, long taskId,
                                     RpcWatchdog.Token watchdog) {
        this.cid = cid;
        this.attempt = attempt;
        this.taskId = taskId;
        this.watchdog = watchdog;
    }

    static Object create(ClassLoader cl, String cid, int attempt, long taskId,
                         RpcWatchdog.Token watchdog) throws Exception {
        Class<?> iface = Class.forName(
                "com.alibaba.android.dingtalkbase.rpc.ApiEventListener", true, cl);
        return Proxy.newProxyInstance(cl, new Class[]{iface},
                new ChatContextListenerProxy(cid, attempt, taskId, watchdog));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        try {
            if ("onDataReceived".equals(name) && args != null && args.length > 0) {
                if (!watchdog.claim()) return null;
                handleData(args[0]);
            } else if ("onException".equals(name) || "onFailure".equals(name)) {
                if (!watchdog.claim()) return null;
                NextGroupFetcher.onFetchFailure(
                        cid, attempt, taskId, RetryPolicy.describe(args, "获取置顶卡片失败"));
            }
        } catch (Throwable t) {
            SilentJoin.log("ChatContext proxy ERR: " + t);
            if ("onDataReceived".equals(name)
                    || "onException".equals(name)
                    || "onFailure".equals(name)) {
                NextGroupFetcher.onFetchFailure(
                        cid, attempt, taskId, "解析置顶数据失败：" + t);
            }
        }
        return null;
    }

    private static Long parseLongId(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Long) return (Long) raw;
        if (raw instanceof Integer) return ((Integer) raw).longValue();
        if (raw instanceof Short) return ((Short) raw).longValue();
        if (raw instanceof Number) return ((Number) raw).longValue();
        if (raw instanceof String) {
            String s = (String) raw;
            if (s.isEmpty()) return null;
            try { return Long.parseLong(s.trim()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private void handleData(Object result) {
        if (!SilentJoin.isActive(taskId)) return;
        if (!(result instanceof Collection)) {
            NextGroupFetcher.onNoCard(cid, attempt, taskId);
            return;
        }
        // 收集群里所有可用置顶卡（一个群可能多张：公告/文本 + 链接卡），
        // 一起交给 WaveSDK 批量拉，避免卡在第一张没链接的卡上。
        java.util.ArrayList<WaveCardFetcher.BoxRef> boxes = new java.util.ArrayList<>();
        for (Object ctxObj : (Collection<?>) result) {
            Object topInteraction = XposedHelpers.getObjectField(ctxObj, "topInteractionObject");
            if (topInteraction == null) continue;

            Object boxList = XposedHelpers.getObjectField(topInteraction, "boxObjectList");
            if (!(boxList instanceof Collection)) continue;

            for (Object box : (Collection<?>) boxList) {
                Object boxData = XposedHelpers.getObjectField(box, "boxData");
                if (boxData == null) continue;

                String boxId = (String) XposedHelpers.getObjectField(box, "boxId");
                String platform = null;
                long msgId = 0L;
                try { platform = (String) XposedHelpers.getObjectField(boxData, "distributePlatform"); }
                catch (Throwable ignored) {}
                try {
                    Object mv = XposedHelpers.getObjectField(boxData, "msgId");
                    Long ml = parseLongId(mv);
                    if (ml != null) msgId = ml;
                } catch (Throwable ignored) {}

                // 多路径提取卡片唯一 ID（修复 cardInstanceId 是 String 类型时 getLongField 读成 0 / 报错导致漏判 "明明有置顶卡片却说没有"）
                Long cardInstanceId = null;
                // ① long 字段
                try { cardInstanceId = (Long) XposedHelpers.callMethod(boxData, "getCardInstanceId"); }
                catch (Throwable ignored) {}
                if (cardInstanceId == null) try {
                    cardInstanceId = parseLongId(XposedHelpers.getObjectField(boxData, "cardInstanceId"));
                } catch (Throwable ignored) {}
                // ② id 字段
                if (cardInstanceId == null || cardInstanceId <= 0) {
                    try { cardInstanceId = parseLongId(XposedHelpers.getObjectField(boxData, "id")); }
                    catch (Throwable ignored) {}
                }
                // ③ 最终 fallback：boxId != null 就认为 box 存在，临时置为 1，让 DingtalkWaveIService/spaceId=cid 去按 boxId 拉
                if (cardInstanceId == null || cardInstanceId <= 0) {
                    if (boxId != null) cardInstanceId = 1L;
                }

                // 把 boxData.toString() 全打进 probe 日志，下次用户"明明有却说没有"时就有实锤
                SilentJoin.log("[NEXT_FETCH] cardInstance=" + cardInstanceId
                        + " boxId=" + boxId + " platform=" + platform + " msgId=" + msgId
                        + " boxData=" + boxData);

                if (cardInstanceId == null || cardInstanceId <= 0) {
                    SilentJoin.log("[NEXT_FETCH] skip box (no usable ID): boxData=" + boxData);
                    continue;
                }

                // 收集，不再命中第一张就 return —— 一个群里所有置顶卡都要一起拉
                boxes.add(new WaveCardFetcher.BoxRef(cardInstanceId, boxId, platform, msgId));
            }
        }

        if (boxes.isEmpty()) {
            NextGroupFetcher.onNoCard(cid, attempt, taskId);
            return;
        }
        // 单通道：WaveSDK（rwm.q）批量拉全部置顶卡；回调逐张扫链接，命中即加群。
        // DingtalkWaveIService(CardDataFetcher) 通道被服务端拒"系统繁忙"，已移除。
        SilentJoin.log("[NEXT_FETCH] usable boxes=" + boxes.size());
        NextGroupFetcher.onChatContextResult(cid, boxes, taskId);
    }
}
