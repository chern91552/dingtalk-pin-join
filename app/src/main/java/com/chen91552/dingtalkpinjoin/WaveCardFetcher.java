package com.chen91552.dingtalkpinjoin;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * 通过钉钉 WaveCard SDK 获取卡片数据。
 * <p>
 * 通路（与 OneBoxCardHolder 完全一致）：
 *   nf2.e(cardInstanceId, cid, boxId, distributePlatform, msgId)
 *     → CardRequestObject
 *   rwm.k(req) → WaveCardParam
 *   WaveCardSDKManager.q().r() → ServiceWaveCard
 *   rwm.q(svc, [param], callback) → onSucceed(ArrayList<WaveCardModel>)
 *   model.cardViewData.get("cardData") → JSON string → nexturl
 */
public class WaveCardFetcher {

    /** 一个可拉取的置顶卡片描述（一个 boxObjectList 里可能有多张）。 */
    static class BoxRef {
        final long cardInstanceId;
        final String boxId;
        final String distributePlatform;
        final long msgId;

        BoxRef(long cardInstanceId, String boxId, String distributePlatform, long msgId) {
            this.cardInstanceId = cardInstanceId;
            this.boxId = boxId;
            this.distributePlatform = distributePlatform;
            this.msgId = msgId;
        }
    }

    /**
     * 批量拉取一组置顶卡片。rwm.q 本身就是 list API：一次请求带 N 个 param，
     * 回调返回 N 个 model。因此把群里所有可用置顶卡一起拉，回调再逐个扫链接，
     * 避免"第一个置顶是公告/文本、第二个才是链接"时卡在第一个上取不到。
     */
    public static void fetchBatch(String cid, ArrayList<BoxRef> boxes, long taskId) {
        fetchBatch(cid, boxes, taskId, 0);
    }

    private static void fetchBatch(String cid, ArrayList<BoxRef> boxes, long taskId,
                                   int retryCount) {
        if (!SilentJoin.isActive(taskId)) return;
        try {
            if (boxes == null || boxes.isEmpty()) {
                SilentJoin.onError(taskId, "该群没有置顶卡片");
                return;
            }
            ClassLoader cl = MainHook.getClassLoader();

            Class<?> nf2 = Class.forName("nf2", true, cl);
            Class<?> rwm = Class.forName("rwm", true, cl);

            // 为每张卡构建一个 WaveCardParam，全部放进一个 list
            ArrayList<Object> paramList = new ArrayList<>();
            for (BoxRef b : boxes) {
                // nf2.e() → CardRequestObject
                Object cardReq = XposedHelpers.callStaticMethod(nf2, "e",
                        b.cardInstanceId, cid, b.boxId, b.distributePlatform,
                        String.valueOf(b.msgId));
                // rwm.k() → WaveCardParam
                Object waveParam = XposedHelpers.callStaticMethod(rwm, "k", cardReq);
                // param.ext.put("formatJSDisabled", "1")
                try {
                    @SuppressWarnings("unchecked")
                    HashMap<String, String> ext = (HashMap<String, String>)
                            XposedHelpers.getObjectField(waveParam, "ext");
                    if (ext != null) ext.put("formatJSDisabled", "1");
                } catch (Throwable ignored) {}
                paramList.add(waveParam);
            }

            // WaveCardSDKManager.q().r()
            Class<?> sdkMgr = Class.forName(
                    "com.alibaba.android.dingtalk.wave.WaveCardSDKManager", true, cl);
            Object sdk = XposedHelpers.callStaticMethod(sdkMgr, "q");
            Object svc = XposedHelpers.callMethod(sdk, "r");

            // callback proxy
            Class<?> callbackCls = Class.forName(
                    "com.dingtalk.nest.wave_card.WaveCardModelListCallBack", true, cl);
            RpcWatchdog.Token watchdog = RpcWatchdog.arm(
                    taskId, "读取卡片",
                    () -> onWaveCardFailure(
                            "请求超时", cid, boxes, retryCount, taskId));
            Object callback = WaveCardCallbackProxy.create(
                    cl, callbackCls, cid, boxes, taskId, retryCount, watchdog);

            // rwm.q(svc, paramList, callback)
            try {
                XposedHelpers.callStaticMethod(rwm, "q", svc, paramList, callback);
            } catch (Throwable t) {
                watchdog.claim();
                throw t;
            }
            SilentJoin.log("WaveCard fetch sent count=" + paramList.size());
        } catch (Throwable t) {
            SilentJoin.log("WaveCardFetcher ERR: " + t);
            String error = String.valueOf(t);
            if (!RetryPolicy.retryIfTransient(taskId, "读取卡片", retryCount, error,
                    () -> fetchBatch(cid, boxes, taskId, retryCount + 1))) {
                SilentJoin.onError(
                        taskId, RetryPolicy.userMessage(error, "获取卡片数据失败"));
            }
        }
    }

    /**
     * cardData 里可能承载加群链接的候选键，按优先级排列。
     * ob_linkUrl（contentCard 内容卡）、nexturl（.schema 拼车卡）是最常见的两种；
     * 其余为 lippi-imopen 等 topbox-open-common 卡型出现过的 url 类键。
     */
    private static final String[] LINK_KEYS = {
            "ob_linkUrl", "nexturl", "ob_jumpUrl", "ob_pc_jumpUrl",
            "mobileUrl", "pcUrl", "addToUrl", "url",
    };

    /**
     * 解析 WaveCardModel 列表，提取 cardData JSON 中的下一群链接。
     * 逐个候选键取值，只接受真正含 code= 的 joingroup 链接，避免误取无关跳转 url。
     */
    static void onWaveCardResult(ArrayList<?> models, long taskId) {
        if (!SilentJoin.isActive(taskId)) return;
        try {
            if (models == null || models.isEmpty()) {
                SilentJoin.onError(taskId, "获取卡片数据失败");
                return;
            }
            // 逐个 model 扫链接：只要有一张卡带链接就用它，全部没有才报错。
            // 覆盖"一个群多张置顶（公告卡在前、链接卡在后）"的情况。
            for (int i = 0; i < models.size(); i++) {
                Object model = models.get(i);
                if (model == null) continue;
                HashMap<String, Object> cardViewData;
                try {
                    @SuppressWarnings("unchecked")
                    HashMap<String, Object> cvd = (HashMap<String, Object>)
                            XposedHelpers.getObjectField(model, "cardViewData");
                    cardViewData = cvd;
                } catch (Throwable ignored) {
                    continue;
                }
                if (cardViewData == null) continue;
                String cardData = (String) cardViewData.get("cardData");
                if (cardData == null) continue;

                String linkUrl = pickLink(cardData);
                if (linkUrl == null) {
                    // 未命中任一候选键：dump 原始 cardData，便于补齐新卡型的键名
                    SilentJoin.log("WaveCard model[" + i + "] no link cardData=" + cardData);
                    continue;
                }
                SilentJoin.log("WaveCard linkUrl=" + linkUrl + " (model[" + i + "])");
                SilentJoin.onNextUrl(linkUrl, taskId);
                return;
            }
            // 所有 model 都没有链接
            SilentJoin.onError(taskId, "卡片中没有下一群链接");
        } catch (Throwable t) {
            SilentJoin.log("onWaveCardResult ERR: " + t);
            SilentJoin.onError(taskId, "解析卡片数据失败");
        }
    }

    /**
     * 从 cardData 中挑出加群链接：
     *   ① 快路径：按候选键顺序取值，命中含 code= 的链接立即返回（常见卡走这条，不跑正则）；
     *   ② 兜底：全文正则找 joingroup 链接，URLDecode 后再判 code=，
     *      覆盖未列到的新键名、以及链接被 encode 进 deeplink（code%3D）的情况。
     */
    static String pickLink(String cardData) {
        // ① 快路径：候选键 + 明文 code=
        for (String key : LINK_KEYS) {
            String v = jstr(cardData, key);
            if (v != null && v.contains("code=")) {
                return v;
            }
        }
        // ② 兜底：全文扫 joingroup 链接（兼容明文与 encode）
        try {
            java.util.regex.Matcher m = JOINGROUP_PATTERN.matcher(cardData);
            while (m.find()) {
                String u = m.group();
                if (u.contains("code=")) {
                    return u;
                }
                String dec = java.net.URLDecoder.decode(u, "UTF-8");
                if (dec.contains("code=")) {
                    return dec;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 匹配 joingroup 链接（明文或被 encode 进 deeplink 的 %2Fjoingroup 形式）。 */
    private static final java.util.regex.Pattern JOINGROUP_PATTERN =
            java.util.regex.Pattern.compile("https?[^\"\\\\\\s]*joingroup[^\"\\\\\\s]*");

    /** 从 JSON 文本中取键 key 的字符串值，找不到返回 null（值截到下一个引号）。 */
    private static String jstr(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    static void onWaveCardFailure(Object error, String cid, ArrayList<BoxRef> boxes,
                                  int retryCount, long taskId) {
        String detail = RetryPolicy.describe(new Object[]{error}, "获取卡片数据失败");
        SilentJoin.log("WaveCard failure: " + detail);
        if (!RetryPolicy.retryIfTransient(taskId, "读取卡片", retryCount, detail,
                () -> fetchBatch(cid, boxes, taskId, retryCount + 1))) {
            SilentJoin.onError(
                    taskId, RetryPolicy.userMessage(detail, "获取卡片数据失败"));
        }
    }
}
