package com.chen91552.dingtalkpinjoin;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

public class NextGroupFetcher {

    private static final int POLL_INTERVAL_MS = 150;
    // 置顶卡片是随 chatcontext 同步下发的：有卡的群第一次 RPC 就命中，
    // 没卡的群刷多少次都是空。故只保留少量重试兜单次 RPC 抖动即可（原为 20）。
    private static final int MAX_POLLS = 3;

    private static final ConcurrentHashMap<String, Boolean> polling = new ConcurrentHashMap<>();

    public static void schedule(final String cid) {
        if (polling.putIfAbsent(cid, Boolean.TRUE) != null) return;
        poll(cid, 0);
    }

    private static void poll(final String cid, final int attempt) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (attempt > 0) Thread.sleep(POLL_INTERVAL_MS);
                    if (!SilentJoin.running) {
                        polling.remove(cid);
                        return;
                    }
                    fetchChatContext(cid, attempt);
                } catch (InterruptedException ignored) {
                    polling.remove(cid);
                } catch (Throwable t) {
                    polling.remove(cid);
                    SilentJoin.log("NextFetcher ERR: " + t);
                }
            }
        }).start();
    }

    private static void fetchChatContext(String cid, int attempt) throws Exception {
        ClassLoader cl = MainHook.getClassLoader();

        Class<?> q8i = Class.forName("q8i", true, cl);
        Object api = XposedHelpers.callStaticMethod(q8i, "u");
        api = XposedHelpers.callMethod(api, "d");

        Class<?> reqCls = Class.forName(
                "com.alibaba.android.dingtalkim.chatcontext.idl.ChatContextRequestModel", true, cl);
        Object req = reqCls.newInstance();
        XposedHelpers.setObjectField(req, "type", "TOP_INTERACTION");

        ArrayList<Object> list = new ArrayList<>();
        list.add(req);

        Object listener = ChatContextListenerProxy.create(cl, cid, attempt);
        XposedHelpers.callMethod(api, "getChatContextByTypes", cid, list, listener);
    }

    static void onChatContextResult(String cid, ArrayList<WaveCardFetcher.BoxRef> boxes) {
        polling.remove(cid);
        try {
            WaveCardFetcher.fetchBatch(cid, boxes);
        } catch (Throwable t) {
            SilentJoin.log("onChatContextResult ERR: " + t);
            SilentJoin.onError("获取卡片数据失败");
        }
    }

    static void onNoCard(String cid, int attempt) {
        if (!SilentJoin.running) {
            polling.remove(cid);
            return;
        }
        if (attempt + 1 < MAX_POLLS) {
            poll(cid, attempt + 1);
        } else {
            polling.remove(cid);
            SilentJoin.onError("该群没有置顶卡片");
        }
    }
}
