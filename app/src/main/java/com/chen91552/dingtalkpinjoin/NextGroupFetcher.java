package com.chen91552.dingtalkpinjoin;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

public class NextGroupFetcher {

    private static final int POLL_INTERVAL_MS = 150;
    // 置顶卡片是随 chatcontext 同步下发的：有卡的群第一次 RPC 就命中，
    // 没卡的群刷多少次都是空。故只保留少量重试兜单次 RPC 抖动即可（原为 20）。
    private static final int MAX_POLLS = 3;

    private static final ConcurrentHashMap<String, Long> polling = new ConcurrentHashMap<>();

    public static void schedule(final String cid, final long taskId) {
        if (!SilentJoin.isActive(taskId)) return;
        if (polling.putIfAbsent(cid, taskId) != null) return;
        poll(cid, 0, taskId);
    }

    private static void poll(final String cid, final int attempt, final long taskId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (attempt > 0) Thread.sleep(POLL_INTERVAL_MS);
                    if (!SilentJoin.isActive(taskId)) {
                        polling.remove(cid, taskId);
                        return;
                    }
                    fetchChatContext(cid, attempt, taskId);
                } catch (InterruptedException ignored) {
                    polling.remove(cid, taskId);
                } catch (Throwable t) {
                    polling.remove(cid, taskId);
                    SilentJoin.log("NextFetcher ERR: " + t);
                    SilentJoin.onError(taskId, "获取置顶卡片失败");
                }
            }
        }).start();
    }

    private static void fetchChatContext(String cid, int attempt, long taskId) throws Exception {
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

        Object listener = ChatContextListenerProxy.create(cl, cid, attempt, taskId);
        XposedHelpers.callMethod(api, "getChatContextByTypes", cid, list, listener);
    }

    static void onChatContextResult(String cid, ArrayList<WaveCardFetcher.BoxRef> boxes,
                                    long taskId) {
        polling.remove(cid, taskId);
        if (!SilentJoin.isActive(taskId)) return;
        try {
            WaveCardFetcher.fetchBatch(cid, boxes, taskId);
        } catch (Throwable t) {
            SilentJoin.log("onChatContextResult ERR: " + t);
            SilentJoin.onError(taskId, "获取卡片数据失败");
        }
    }

    static void onNoCard(String cid, int attempt, long taskId) {
        if (!SilentJoin.isActive(taskId)) {
            polling.remove(cid, taskId);
            return;
        }
        if (attempt + 1 < MAX_POLLS) {
            poll(cid, attempt + 1, taskId);
        } else {
            polling.remove(cid, taskId);
            SilentJoin.onError(taskId, "该群没有置顶卡片");
        }
    }

    static void onFetchFailure(String cid, int attempt, long taskId, String error) {
        if (!SilentJoin.isActive(taskId)) {
            polling.remove(cid, taskId);
            return;
        }
        if (RetryPolicy.retryIfTransient(taskId, "读取置顶", attempt, error,
                () -> poll(cid, attempt + 1, taskId))) {
            return;
        }
        polling.remove(cid, taskId);
        SilentJoin.onError(taskId, RetryPolicy.userMessage(error, "获取置顶卡片失败"));
    }

    static void cancelAll() {
        polling.clear();
    }
}
