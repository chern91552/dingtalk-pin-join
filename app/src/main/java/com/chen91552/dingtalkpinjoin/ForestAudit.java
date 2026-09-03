package com.chen91552.dingtalkpinjoin;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XposedHelpers;

public final class ForestAudit {

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_STOPPED = 3;
    public static final int STATUS_ERROR = 4;
    private static final String DETAIL_URI = "/r/Adaptor/CooperateI/getDetailNew";
    private static final String CERT_LIST_URI =
            "/r/Adaptor/CoPlantCertificateQueryI/queryCertificateList";
    private static final String CERT_DETAIL_URI =
            "/r/Adaptor/CoPlantCertificateQueryI/queryCertificate";
    private static final long TIMEOUT_MS = 15000L;
    private static final long TERMINAL_STATUS_VISIBLE_MS = 5000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong();
    private static final Set<String> SEEN_CIDS = new HashSet<>();
    private static final Set<String> SEEN_CODES = new HashSet<>();
    private static final Map<String, Integer> TREE_COUNTS = new LinkedHashMap<>();
    private static final Set<StatusListener> STATUS_LISTENERS =
            new CopyOnWriteArraySet<>();

    private static volatile boolean running;
    private static volatile long activeTaskId;
    private static volatile int target;
    private static volatile int processed;
    private static volatile int enabledTrees;
    private static volatile int certificates;
    private static volatile int claimed;
    private static volatile int status = STATUS_IDLE;
    private static volatile String statusDetail = "";
    private static volatile String currentGroupName = "";
    private static volatile long statusExpiresAt;

    private ForestAudit() {}

    public static synchronized void start(String cid, int count) {
        if (running) {
            Toaster.show("已有置顶查证任务正在运行");
            return;
        }
        if (JoinLoop.running) {
            Toaster.show("请先停止置顶加群任务");
            return;
        }
        if (SilentJoin.running) {
            Toaster.show("请先停止静默加群任务");
            return;
        }
        activeTaskId = TASK_SEQUENCE.incrementAndGet();
        running = true;
        status = STATUS_RUNNING;
        statusDetail = "";
        statusExpiresAt = 0L;
        target = Math.max(1, count);
        processed = 0;
        enabledTrees = 0;
        certificates = 0;
        claimed = 0;
        currentGroupName = conversationTitle(cid);
        SEEN_CIDS.clear();
        SEEN_CODES.clear();
        TREE_COUNTS.clear();
        notifyStatusChanged();
        summary("[置顶查证] 开始，共 " + target + " 个群");
        Toaster.show("置顶查证开始，共 " + target + " 个群");
        inspectGroup(cid, currentGroupName, activeTaskId);
    }

    public static synchronized void stop() {
        if (!running) return;
        running = false;
        activeTaskId = TASK_SEQUENCE.incrementAndGet();
        status = STATUS_STOPPED;
        statusDetail = "已停止 " + processed + "/" + target;
        statusExpiresAt = android.os.SystemClock.elapsedRealtime()
                + TERMINAL_STATUS_VISIBLE_MS;
        summary("[置顶查证] 已停止，进度 " + processed + "/" + target);
        summary(statistics());
        notifyStatusChanged();
        Toaster.show("置顶查证已停止");
    }

    public static boolean isRunning() {
        return running;
    }

    static boolean isActive(long taskId) {
        return running && activeTaskId == taskId;
    }

    private static void inspectGroup(String cid, String groupNameHint, long taskId) {
        if (!isActive(taskId)) return;
        synchronized (ForestAudit.class) {
            if (!SEEN_CIDS.add(cid)) {
                finish(taskId, "检测到重复群");
                return;
            }
        }
        String fallbackName = nonEmpty(groupNameHint, conversationTitle(cid));
        currentGroupName = fallbackName;
        notifyStatusChanged();
        try {
            JSONObject request = new JSONObject();
            request.put("conversationId", cid);
            request.put("URLFrom", "");
            call(DETAIL_URI, new JSONArray().put(request).toString(), taskId,
                    response -> handleTreeDetail(cid, fallbackName, response, taskId),
                    error -> handleQueryFailure(
                            cid, fallbackName, "查询公益树失败", error, taskId));
        } catch (Throwable t) {
            handleQueryFailure(
                    cid, fallbackName, "查询公益树失败", String.valueOf(t), taskId);
        }
    }

    private static void handleTreeDetail(String cid, String fallbackName,
                                         JSONObject response, long taskId) {
        if (!isActive(taskId)) return;
        JSONObject group = response.optJSONObject("result");
        if (group == null) {
            recordFailureAndContinue(
                    cid, fallbackName, "查询公益树失败", "响应缺少 result", taskId);
            return;
        }
        group = group == null ? null : group.optJSONObject("groupDetail");
        if (group == null) {
            recordFailureAndContinue(
                    cid, fallbackName, "查询公益树失败", "响应缺少 groupDetail", taskId);
            return;
        }
        JSONObject tree = group == null ? null : group.optJSONObject("cooperateDetail");
        if (tree == null || !"NORMAL".equalsIgnoreCase(tree.optString("status"))) {
            recordAndContinue(
                    cid, fallbackName, "[未开通公益树]", taskId);
            return;
        }

        enabledTrees++;
        String groupName = fallbackName;
        try {
            JSONObject request = new JSONObject();
            request.put("entityId", parseCid(cid));
            request.put("cooperateBizType", "GROUP");
            call(CERT_LIST_URI, new JSONArray().put(request).toString(), taskId,
                    result -> handleCertificateList(cid, groupName, result, taskId),
                    error -> handleQueryFailure(
                            cid, groupName, "证书查询失败", error, taskId));
        } catch (Throwable t) {
            handleQueryFailure(
                    cid, groupName, "证书查询失败", String.valueOf(t), taskId);
        }
    }

    private static void handleCertificateList(String cid, String groupName,
                                              JSONObject response, long taskId) {
        if (!isActive(taskId)) return;
        JSONArray list = response.optJSONArray("result");
        if (list == null) {
            recordFailureAndContinue(
                    cid, groupName, "证书查询失败", "响应缺少 result", taskId);
            return;
        }
        if (list.length() == 0) {
            recordAndContinue(
                    cid, groupName, "[公益树已开通][无证书]", taskId);
            return;
        }

        certificates++;
        JSONObject first = list.optJSONObject(0);
        if (first == null) {
            recordAndContinue(
                    cid, groupName, "[公益树已开通][证书解析失败]", taskId);
            return;
        }
        long certificateId = first.optLong("certificateId");
        try {
            JSONObject request = new JSONObject();
            request.put("certificateId", certificateId);
            request.put("entityId", parseCid(cid));
            request.put("cooperateBizType", "GROUP");
            call(CERT_DETAIL_URI, new JSONArray().put(request).toString(), taskId,
                    result -> handleCertificateDetail(
                            cid, groupName, first, result, taskId),
                    error -> {
                        if (isNotMemberError(error)) {
                            stopNotJoined(cid, groupName, taskId);
                            return;
                        }
                        log("certificate detail failed cid=" + cid + ": " + error);
                        recordCertificate(
                                cid, groupName, first, null,
                                "未知（详情查询失败）", taskId);
                    });
        } catch (Throwable t) {
            log("certificate detail failed cid=" + cid + ": " + t);
            recordCertificate(
                    cid, groupName, first, null,
                    "未知（详情查询失败）", taskId);
        }
    }

    private static void handleCertificateDetail(String cid, String groupName,
                                                JSONObject first, JSONObject response,
                                                long taskId) {
        if (!isActive(taskId)) return;
        JSONObject detail = response.optJSONObject("result");
        if (detail == null) {
            log("certificate detail missing result cid=" + cid);
            recordCertificate(
                    cid, groupName, first, null,
                    "未知（详情为空）", taskId);
            return;
        }
        String userName = detail == null ? "" : detail.optString("userName");
        boolean hasClaimed = !userName.trim().isEmpty();
        recordCertificate(
                cid, groupName, first, detail,
                hasClaimed ? "是" : "否", taskId);
    }

    private static synchronized void recordCertificate(
            String cid, String groupName, JSONObject first,
            JSONObject detail, String claimStatus, long taskId) {
        if (!isActive(taskId)) return;
        JSONObject source = detail != null ? detail : first;
        String number = nonEmpty(
                source.optString("alipayCertificateId"),
                first.optString("alipayCertificateId"));
        long createTime = source.optLong(
                "gmtCreate", first.optLong("gmtCreate"));
        String featureText = nonEmpty(
                source.optString("feature"), first.optString("feature"));
        String treeName = "";
        try {
            treeName = new JSONObject(featureText).optString("treeName");
        } catch (Throwable ignored) {}
        treeName = nonEmpty(treeName, "未知");
        Integer count = TREE_COUNTS.get(treeName);
        TREE_COUNTS.put(treeName, count == null ? 1 : count + 1);
        if ("是".equals(claimStatus)) claimed++;
        String message = "[公益树已开通][有证书]"
                + " 本人领证=" + claimStatus
                + "｜时间=" + formatTime(createTime)
                + "｜编号=" + nonEmpty(number, "未知")
                + "｜树种=" + treeName;
        recordAndContinue(cid, groupName, message, taskId);
    }

    private static synchronized void recordAndContinue(
            String cid, String groupName, String result, long taskId) {
        if (!isActive(taskId)) return;
        processed++;
        summary("[置顶查证] " + processed + "/" + target + " "
                + nonEmpty(groupName, cid) + "：" + result);
        currentGroupName = nonEmpty(groupName, cid);
        notifyStatusChanged();
        if (processed >= target) {
            finish(taskId, "达到指定数量");
            return;
        }
        resolveNext(cid, taskId);
    }

    private static void recordFailureAndContinue(String cid, String groupName,
                                                 String userMessage, String detail,
                                                 long taskId) {
        log(userMessage + " cid=" + cid + ": " + detail);
        recordAndContinue(
                cid, groupName, "[查证失败] " + userMessage, taskId);
    }

    private static void handleQueryFailure(String cid, String groupName,
                                           String userMessage, String detail,
                                           long taskId) {
        if (isNotMemberError(detail)) {
            stopNotJoined(cid, groupName, taskId);
            return;
        }
        recordFailureAndContinue(cid, groupName, userMessage, detail, taskId);
    }

    private static boolean isNotMemberError(String detail) {
        String text = detail == null ? "" : detail;
        return text.contains("只有群成员")
                || text.contains("不是群成员")
                || text.contains("非群成员")
                || text.toLowerCase(Locale.ROOT).contains("not a member");
    }

    private static void resolveNext(String cid, long taskId) {
        StageToken token = arm(taskId, "解析下一群链接",
                () -> finishFailure(
                        taskId, "解析下一群链接超时", "timeout"));
        ForestAuditNextFetcher.resolve(cid, taskId, new ForestAuditNextFetcher.Callback() {
            @Override
            public void onLink(String link) {
                if (!token.claim() || !isActive(taskId)) return;
                String code = SilentJoin.extractParam(link, "code");
                String origin = SilentJoin.extractParam(link, "origin");
                if (code == null || code.isEmpty()) {
                    finishFailure(
                            taskId, "下一群链接无邀请码", link);
                    return;
                }
                synchronized (ForestAudit.class) {
                    if (!SEEN_CODES.add(code)) {
                        finish(taskId, "检测到重复置顶链接");
                        return;
                    }
                }
                verifyNext(code, origin == null ? "11" : origin, taskId);
            }

            @Override
            public void onFailure(String error) {
                if (!token.claim() || !isActive(taskId)) return;
                finishFailure(taskId, "无法读取下一群链接", error);
            }
        });
    }

    private static void verifyNext(String code, String origin, long taskId) {
        if (!isActive(taskId)) return;
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> modelClass = XposedHelpers.findClass(
                    "com.alibaba.wukong.idl.im.models.VerifyModel", cl);
            Object model = modelClass.newInstance();
            XposedHelpers.setObjectField(model, "code", code);
            int originValue = 11;
            try {
                originValue = Integer.parseInt(origin);
            } catch (Throwable ignored) {}
            XposedHelpers.setObjectField(model, "origin", originValue);

            Class<?> serviceClass = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.ConversationService", cl);
            Class<?> engineClass = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.IMEngine", cl);
            Object service = XposedHelpers.callStaticMethod(
                    engineClass, "getIMService", serviceClass);
            Class<?> callbackClass = Class.forName(
                    "com.alibaba.wukong.Callback", true, cl);
            StageToken token = arm(
                    taskId, "验证下一群链接",
                    () -> finishFailure(
                            taskId, "验证下一群链接超时", "timeout"));
            Object callback = Proxy.newProxyInstance(
                    cl, new Class<?>[]{callbackClass},
                    new VerifyHandler(taskId, token));
            XposedHelpers.callMethod(service, "verifyCodeV2", callback, model);
        } catch (Throwable t) {
            finishFailure(
                    taskId, "验证下一群链接失败", String.valueOf(t));
        }
    }

    private static final class VerifyHandler implements InvocationHandler {
        private final long taskId;
        private final StageToken token;

        VerifyHandler(long taskId, StageToken token) {
            this.taskId = taskId;
            this.token = token;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("onSuccess".equals(name)) {
                if (!token.claim() || !isActive(taskId)) return null;
                try {
                    Object card = args != null && args.length > 0 ? args[0] : null;
                    Object conversation = card == null ? null
                            : XposedHelpers.callMethod(card, "getConversation");
                    String cid = conversation == null ? null : (String)
                            XposedHelpers.callMethod(conversation, "conversationId");
                    Object title = conversation == null ? null
                            : XposedHelpers.callMethod(conversation, "title");
                    String groupName = title == null ? null : String.valueOf(title);
                    if (cid == null || cid.isEmpty()) {
                        finish(taskId, "下一群信息为空");
                    } else if (conversationFromMemory(cid) == null) {
                        stopNotJoined(cid, groupName, taskId);
                    } else {
                        inspectGroup(cid, groupName, taskId);
                    }
                } catch (Throwable t) {
                    finishFailure(
                            taskId, "解析下一群失败", String.valueOf(t));
                }
            } else if ("onException".equals(name) || "onFailure".equals(name)) {
                if (!token.claim() || !isActive(taskId)) return null;
                finishFailure(
                        taskId, "验证下一群链接失败",
                        RetryPolicy.describe(args, "验证下一群链接失败"));
            }
            return null;
        }
    }

    private static void call(String uri, String body, long taskId,
                             JsonSuccess success, StringFailure failure) {
        StageToken token = arm(taskId, uri, () -> failure.onFailure("请求超时"));
        LwpClient.call(uri, body, new LwpClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                if (!token.claim() || !isActive(taskId)) return;
                String error = businessError(response);
                if (error == null) {
                    success.onSuccess(response);
                } else {
                    failure.onFailure(error);
                }
            }

            @Override
            public void onFailure(String error) {
                if (token.claim() && isActive(taskId)) failure.onFailure(error);
            }
        });
    }

    private static String businessError(JSONObject response) {
        if (response == null) return "响应为空";
        if (!response.has("success") || response.optBoolean("success")) return null;
        String code = nonEmpty(
                response.optString("errorCode"),
                response.optString("code"));
        String message = nonEmpty(
                response.optString("errorMsg"),
                nonEmpty(response.optString("message"), "业务请求失败"));
        return code.isEmpty() ? message : code + " " + message;
    }

    private static StageToken arm(long taskId, String stage, Runnable timeout) {
        StageToken token = new StageToken();
        MAIN.postDelayed(() -> {
            if (!token.claim() || !isActive(taskId)) return;
            log("timeout stage=" + stage);
            timeout.run();
        }, TIMEOUT_MS);
        return token;
    }

    private static synchronized void finish(long taskId, String reason) {
        if (!isActive(taskId)) return;
        running = false;
        boolean completed = processed >= target;
        status = completed ? STATUS_COMPLETED : STATUS_STOPPED;
        statusDetail = completed
                ? "已完成 " + processed + "/" + target
                : "已结束 " + processed + "/" + target + " · " + reason;
        statusExpiresAt = android.os.SystemClock.elapsedRealtime()
                + TERMINAL_STATUS_VISIBLE_MS;
        summary("[置顶查证] 结束：" + reason);
        summary(statistics());
        notifyStatusChanged();
        if (completed) {
            Toaster.show("置顶查证完成，共查询 " + processed + " 个群");
        } else {
            Toaster.show("置顶查证结束：" + reason);
        }
    }

    private static synchronized void finishFailure(
            long taskId, String userMessage, String detail) {
        if (!isActive(taskId)) return;
        log(userMessage + ": " + detail);
        running = false;
        status = STATUS_ERROR;
        statusDetail = "异常停止 " + processed + "/" + target + " · " + userMessage;
        statusExpiresAt = android.os.SystemClock.elapsedRealtime()
                + TERMINAL_STATUS_VISIBLE_MS;
        summary("[置顶查证] 结束：" + userMessage);
        summary(statistics());
        notifyStatusChanged();
        Toaster.show("置顶查证异常停止，已查询 " + processed + " 个群");
    }

    private static synchronized void stopNotJoined(
            String cid, String groupName, long taskId) {
        if (!isActive(taskId)) return;
        String name = nonEmpty(groupName, cid);
        running = false;
        currentGroupName = name;
        status = STATUS_STOPPED;
        statusDetail = "已停止 " + processed + "/" + target + " · 下一群不在群里";
        statusExpiresAt = android.os.SystemClock.elapsedRealtime()
                + TERMINAL_STATUS_VISIBLE_MS;
        summary("[置顶查证] 下一群 " + name + "：[不在群里]");
        summary("[置顶查证] 已停止：下一群不在群里");
        summary(statistics());
        log("next group not joined cid=" + cid + " name=" + name);
        notifyStatusChanged();
        Toaster.show("置顶查证已停止：下一群不在群里");
    }

    public static void addStatusListener(StatusListener listener) {
        if (listener == null) return;
        STATUS_LISTENERS.add(listener);
        dispatchStatus(listener, getStatusSnapshot());
    }

    public static void removeStatusListener(StatusListener listener) {
        STATUS_LISTENERS.remove(listener);
    }

    public static synchronized StatusSnapshot getStatusSnapshot() {
        long remainingMs = 0L;
        if (!running && status != STATUS_IDLE && statusExpiresAt > 0L) {
            remainingMs = statusExpiresAt - android.os.SystemClock.elapsedRealtime();
            if (remainingMs <= 0L) {
                status = STATUS_IDLE;
                statusDetail = "";
                statusExpiresAt = 0L;
                remainingMs = 0L;
            }
        }
        return new StatusSnapshot(
                status, processed, target, currentGroupName, statusDetail, remainingMs);
    }

    private static void notifyStatusChanged() {
        StatusSnapshot snapshot = getStatusSnapshot();
        for (StatusListener listener : STATUS_LISTENERS) {
            dispatchStatus(listener, snapshot);
        }
    }

    private static void dispatchStatus(StatusListener listener, StatusSnapshot snapshot) {
        try {
            MAIN.post(() -> listener.onStatusChanged(snapshot));
        } catch (Throwable ignored) {}
    }

    private static String statistics() {
        return "[置顶查证统计] 已查询 " + processed + "/" + target
                + "｜公益树 " + enabledTrees
                + "｜有证书 " + certificates
                + "｜本人已领 " + claimed
                + "｜树种统计 " + treeStatistics();
    }

    private static String treeStatistics() {
        if (TREE_COUNTS.isEmpty()) return "无";
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : TREE_COUNTS.entrySet()) {
            if (result.length() > 0) result.append('、');
            result.append(entry.getKey()).append('×').append(entry.getValue());
        }
        return result.toString();
    }

    private static String conversationTitle(String cid) {
        try {
            Object conversation = conversationFromMemory(cid);
            Object title = conversation == null ? null
                    : XposedHelpers.callMethod(conversation, "title");
            if (title != null && !String.valueOf(title).isEmpty()) {
                return String.valueOf(title);
            }
        } catch (Throwable ignored) {}
        return cid;
    }

    private static Object conversationFromMemory(String cid) {
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> serviceClass = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.ConversationService", cl);
            Class<?> engineClass = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.IMEngine", cl);
            Object service = XposedHelpers.callStaticMethod(
                    engineClass, "getIMService", serviceClass);
            return XposedHelpers.callMethod(
                    service, "getConversationFromMemory", cid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object parseCid(String cid) {
        try {
            return Long.parseLong(cid);
        } catch (Throwable ignored) {
            return cid;
        }
    }

    private static String formatTime(long value) {
        if (value <= 0L) return "未知";
        return new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(value));
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static void summary(String message) {
        FileLogger.write(FileLogger.CAT_SUMMARY, message);
    }

    private static void log(String message) {
        FileLogger.write(FileLogger.CAT_SYSTEM, "[FOREST_AUDIT] " + message);
    }

    private interface JsonSuccess {
        void onSuccess(JSONObject response);
    }

    private interface StringFailure {
        void onFailure(String error);
    }

    public interface StatusListener {
        void onStatusChanged(StatusSnapshot snapshot);
    }

    public static final class StatusSnapshot {
        public final int status;
        public final int completed;
        public final int target;
        public final String groupName;
        public final String detail;
        public final long remainingMs;

        StatusSnapshot(int status, int completed, int target, String groupName,
                       String detail, long remainingMs) {
            this.status = status;
            this.completed = completed;
            this.target = target;
            this.groupName = groupName;
            this.detail = detail;
            this.remainingMs = remainingMs;
        }
    }

    private static final class StageToken {
        private final AtomicBoolean claimed = new AtomicBoolean();

        boolean claim() {
            return claimed.compareAndSet(false, true);
        }
    }
}
