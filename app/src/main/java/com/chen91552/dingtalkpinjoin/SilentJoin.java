package com.chen91552.dingtalkpinjoin;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XposedHelpers;

public class SilentJoin {

    private static final String TAG = "SilentJoin";
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_STOPPED = 3;
    public static final int STATUS_ERROR = 4;
    private static final long TERMINAL_STATUS_VISIBLE_MS = 5000L;

    public static volatile boolean running;
    public static volatile boolean clearUnread;
    public static volatile boolean muteNewGroups;
    public static volatile boolean muteAtAll;
    public static volatile int target;
    public static volatile int joinedCount;
    public static volatile int alreadyJoinedCount;
    public static volatile int newlyJoinedCount;
    public static volatile String lastGroupName;
    private static volatile String currentCid;
    private static volatile int noTopCount;
    private static volatile int invalidLinkCount;
    private static volatile int approvalCount;
    private static volatile int requestFailedCount;

    private static final Set<String> seenCodes = new HashSet<>();
    private static final Set<String> joinedCids = new HashSet<>();
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong();
    private static final Set<StatusListener> statusListeners = new CopyOnWriteArraySet<>();
    private static volatile long activeTaskId;
    private static volatile int status = STATUS_IDLE;
    private static volatile String statusDetail = "";
    private static volatile long statusExpiresAt;

    public static synchronized void start(String cid, int count, boolean clearUnreadFlag,
                                          boolean muteNewGroupsFlag, boolean muteAtAllFlag) {
        if (running) {
            Toaster.show("已有静默加群任务正在运行");
            return;
        }
        if (JoinLoop.running) {
            Toaster.show("请先停止置顶加群任务");
            return;
        }
        if (ForestAudit.isRunning()) {
            Toaster.show("请先停止置顶查证任务");
            return;
        }
        long taskId = TASK_SEQUENCE.incrementAndGet();
        activeTaskId = taskId;
        running = true;
        status = STATUS_RUNNING;
        statusDetail = "";
        statusExpiresAt = 0L;
        target = count;
        clearUnread = clearUnreadFlag;
        muteNewGroups = muteNewGroupsFlag;
        muteAtAll = muteNewGroupsFlag && muteAtAllFlag;
        joinedCount = 0;
        alreadyJoinedCount = 0;
        newlyJoinedCount = 0;
        lastGroupName = null;
        currentCid = cid;
        noTopCount = 0;
        invalidLinkCount = 0;
        approvalCount = 0;
        requestFailedCount = 0;
        seenCodes.clear();
        joinedCids.clear();
        notifyStatusChanged();

        summary("静默加群开始，共 " + count + " 个"
                + "（免打扰：" + optionText(muteNewGroups)
                + "，@所有人不提醒：" + optionText(muteAtAll) + "）");
        Toaster.show("🔇 静默加群开始，共 " + count + " 个");
        log("start cid=" + cid + " N=" + count);

        NextGroupFetcher.schedule(cid, taskId);
    }

    public static synchronized void stop() {
        if (!running) return;
        long stoppedTaskId = activeTaskId;
        int stoppedAt = joinedCount;
        running = false;
        activeTaskId = TASK_SEQUENCE.incrementAndGet();
        status = STATUS_STOPPED;
        statusDetail = "已停止 " + stoppedAt + "/" + target;
        statusExpiresAt = android.os.SystemClock.elapsedRealtime() + TERMINAL_STATUS_VISIBLE_MS;
        NextGroupFetcher.cancelAll();
        GroupNotificationSettings.cancel(stoppedTaskId);
        String msg = "用户停止静默加群，已成功处理 " + stoppedAt + "/" + target + " 个群";
        summary("[停止] " + msg);
        summary(buildStatistics());
        log(msg);
        notifyStatusChanged();
        Toaster.show("静默加群已停止");
    }

    public static synchronized void onError(long taskId, String msg) {
        if (!isActive(taskId)) return;
        running = false;
        GroupNotificationSettings.cancel(taskId);
        status = STATUS_ERROR;
        statusDetail = "异常停止 " + joinedCount + "/" + target + " · " + msg;
        statusExpiresAt = android.os.SystemClock.elapsedRealtime() + TERMINAL_STATUS_VISIBLE_MS;
        String category = recordFailure(msg);
        String stat = "已成功处理 " + joinedCount + "/" + target
                + " 个群（新加入 " + newlyJoinedCount + " 个，已加过 " + alreadyJoinedCount + " 个）";
        log("error: " + msg + " | " + stat);
        summary("[" + category + "] " + currentSubject() + "：" + msg);
        summary("[终止] 静默加群任务异常终止");
        summary(buildStatistics());
        notifyStatusChanged();
        Toaster.show("⚠️ 静默加群异常：" + msg + "（已成功 " + joinedCount + " 个）");
    }

    public static void onNextUrl(String nexturl, long taskId) {
        if (!isActive(taskId)) return;
        try {
            String code = extractParam(nexturl, "code");
            String origin = extractParam(nexturl, "origin");
            if (code == null || code.isEmpty()) return;
            synchronized (SilentJoin.class) {
                if (!isActive(taskId) || !seenCodes.add(code)) return;
            }

            log("nexturl code=" + code);
            verifyCode(code, origin != null ? origin : "11", taskId, 0);
        } catch (Throwable t) {
            log("onNextUrl ERR: " + t);
        }
    }

    static void verifyCode(String code, String origin, long taskId, int retryCount) {
        if (!isActive(taskId)) return;
        try {
            ClassLoader cl = MainHook.getClassLoader();

            Class<?> modelCls = XposedHelpers.findClass(
                    "com.alibaba.wukong.idl.im.models.VerifyModel", cl);
            Object model = modelCls.newInstance();
            XposedHelpers.setObjectField(model, "code", code);
            int originInt = 11;
            try { originInt = Integer.parseInt(origin); } catch (Exception ignored) {}
            XposedHelpers.setObjectField(model, "origin", originInt);

            Class<?> svcCls = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.ConversationService", cl);
            Class<?> imEngine = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.IMEngine", cl);
            Object svc = XposedHelpers.callStaticMethod(imEngine, "getIMService", svcCls);

            RpcWatchdog.Token watchdog = RpcWatchdog.arm(
                    taskId, "校验邀请码", () -> {
                        if (!RetryPolicy.retryIfTransient(
                                taskId, "校验邀请码", retryCount, "请求超时",
                                () -> verifyCode(
                                        code, origin, taskId, retryCount + 1))) {
                            onError(taskId, "验证邀请码超时");
                        }
                    });
            Object callback = VerifyCallbackProxy.create(
                    cl, code, originInt, taskId, retryCount, watchdog);
            try {
                XposedHelpers.callMethod(svc, "verifyCodeV2", callback, model);
            } catch (Throwable t) {
                watchdog.claim();
                throw t;
            }
            log("verifyCode sent: " + code);
        } catch (Throwable t) {
            log("verifyCode ERR: " + t);
            String error = String.valueOf(t);
            if (!RetryPolicy.retryIfTransient(taskId, "校验邀请码", retryCount, error,
                    () -> verifyCode(code, origin, taskId, retryCount + 1))) {
                onError(taskId, RetryPolicy.userMessage(error, "验证失败"));
            }
        }
    }

    static void joinGroup(String cid, Object uid, int origin, String code, long taskId,
                          boolean requiresApproval) {
        if (!isActive(taskId)) return;
        currentCid = cid;

        synchronized (SilentJoin.class) {
            if (!isActive(taskId)) return;
            if (!joinedCids.add(cid)) {
                onAlreadyJoined(cid, taskId);
                return;
            }
        }

        // Check if conversation already exists in memory (user already in group)
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> svcCls = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.ConversationService", cl);
            Class<?> imEngine = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.IMEngine", cl);
            Object svc = XposedHelpers.callStaticMethod(imEngine, "getIMService", svcCls);
            Object existing = XposedHelpers.callMethod(svc, "getConversationFromMemory", cid);
            if (existing != null) {
                onAlreadyJoined(cid, taskId);
                return;
            }
        } catch (Throwable ignored) {}

        if (requiresApproval) {
            onError(taskId, "该群需要管理员审批");
            return;
        }

        requestSafeCheck(cid, uid, origin, code, taskId, 0);
    }

    static void requestSafeCheck(String cid, Object uid, int origin, String code,
                                 long taskId, int retryCount) {
        if (!isActive(taskId)) return;
        try {
            ClassLoader cl = MainHook.getClassLoader();

            Class<?> safeReqCls = XposedHelpers.findClass(
                    "com.alibaba.wukong.idl.im.models.PreJoinGroupSafeCheckReqModel", cl);
            Object safeReq = safeReqCls.newInstance();
            XposedHelpers.setObjectField(safeReq, "cid", cid);
            if (uid != null) XposedHelpers.setObjectField(safeReq, "inviter", uid);

            Class<?> safeSvcCls = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.ConversationSafeService", cl);
            Class<?> imEngine = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.IMEngine", cl);
            Object safeSvc = XposedHelpers.callStaticMethod(
                    imEngine, "getIMService", safeSvcCls);

            RpcWatchdog.Token watchdog = RpcWatchdog.arm(
                    taskId, "安全检查", () -> {
                        if (!RetryPolicy.retryIfTransient(
                                taskId, "安全检查", retryCount, "请求超时",
                                () -> requestSafeCheck(
                                        cid, uid, origin, code, taskId, retryCount + 1))) {
                            onError(taskId, "安全检查超时");
                        }
                    });
            Object safeCb = SafeCheckCallbackProxy.create(
                    cl, cid, uid, origin, code, taskId, retryCount, watchdog);
            try {
                XposedHelpers.callMethod(safeSvc, "preJoinGroupSafeCheck", safeReq, safeCb);
            } catch (Throwable t) {
                watchdog.claim();
                throw t;
            }
            log("safeCheck sent cid=" + cid);
        } catch (Throwable t) {
            log("safeCheck ERR: " + t);
            String error = String.valueOf(t);
            if (!RetryPolicy.retryIfTransient(taskId, "安全检查", retryCount, error,
                    () -> requestSafeCheck(
                            cid, uid, origin, code, taskId, retryCount + 1))) {
                onError(taskId, RetryPolicy.userMessage(error, "安全检查失败"));
            }
        }
    }

    static void doAddMember(String cid, Object uid, int origin, String token, String code,
                            long taskId) {
        doAddMember(cid, uid, origin, token, code, taskId, 0);
    }

    static void doAddMember(String cid, Object uid, int origin, String token, String code,
                            long taskId, int retryCount) {
        if (!isActive(taskId)) return;
        try {
            ClassLoader cl = MainHook.getClassLoader();

            Class<?> reqCls = XposedHelpers.findClass(
                    "com.alibaba.android.dingtalkim.models.idl.AddGroupMemberByQrcodeReqModel", cl);
            Object req = reqCls.newInstance();
            XposedHelpers.setObjectField(req, "cid", cid);
            XposedHelpers.setObjectField(req, "token", token);
            XposedHelpers.setObjectField(req, "origin", origin);
            if (uid != null) XposedHelpers.setObjectField(req, "uid", uid);

            Class<?> svcCls = XposedHelpers.findClass(
                    "com.alibaba.android.dingtalkim.models.idl.service.IMIService", cl);
            Class<?> uzi = XposedHelpers.findClass("uzi", cl);
            Object svc = XposedHelpers.callStaticMethod(uzi, "a", svcCls);

            RpcWatchdog.Token watchdog = RpcWatchdog.arm(
                    taskId, "提交加群", () -> {
                        if (!RetryPolicy.retryIfTransient(
                                taskId, "提交加群", retryCount, "请求超时",
                                () -> doAddMember(
                                        cid, uid, origin, token, code,
                                        taskId, retryCount + 1))) {
                            onError(taskId, "加群请求超时");
                        }
                    });
            Object handler = JoinCallbackProxy.create(
                    cl, cid, uid, origin, token, code, taskId, retryCount, watchdog);
            try {
                XposedHelpers.callMethod(svc, "addGroupMemberByQrcodeV4", req, handler);
            } catch (Throwable t) {
                watchdog.claim();
                throw t;
            }
            log("addGroupMember sent cid=" + cid);
        } catch (Throwable t) {
            log("doAddMember ERR: " + t);
            String error = String.valueOf(t);
            if (!RetryPolicy.retryIfTransient(taskId, "提交加群", retryCount, error,
                    () -> doAddMember(
                            cid, uid, origin, token, code, taskId, retryCount + 1))) {
                onError(taskId, RetryPolicy.userMessage(error, "加群请求失败"));
            }
        }
    }

    static synchronized void onJoinOk(String cid, long taskId) {
        if (!isActive(taskId)) return;

        joinedCount++;
        newlyJoinedCount++;
        currentCid = cid;
        String name = lastGroupName != null ? lastGroupName : cid;
        summary("[成功] 新加入群：" + name + "（第 " + joinedCount + "/" + target + " 个）");
        log("joined " + cid + " count=" + joinedCount + "/" + target);
        notifyStatusChanged();

        if (clearUnread) clearUnreadAsync(cid);
        if (muteNewGroups) {
            GroupNotificationSettings.apply(cid, muteAtAll, taskId);
        }

        if (joinedCount % 5 == 0) {
            Toaster.show("✅ 已静默加入第 " + joinedCount + " 个群");
        }
        if (joinedCount >= target) {
            finish(taskId);
            return;
        }

        NextGroupFetcher.schedule(cid, taskId);
    }

    static synchronized void onAlreadyJoined(String cid, long taskId) {
        if (!isActive(taskId)) return;

        joinedCount++;
        alreadyJoinedCount++;
        currentCid = cid;
        String name = lastGroupName != null ? lastGroupName : cid;
        summary("[已加过] " + name + "（第 " + joinedCount + "/" + target + " 个）");
        log("already joined cid=" + cid + " count=" + joinedCount + "/" + target);
        notifyStatusChanged();

        if (clearUnread) clearUnreadAsync(cid);
        if (muteNewGroups) {
            GroupNotificationSettings.apply(cid, muteAtAll, taskId);
        }

        if (joinedCount % 5 == 0) {
            Toaster.show("✅ 已加群第 " + joinedCount + " 个群");
        }

        if (joinedCount >= target) {
            finish(taskId);
            return;
        }

        NextGroupFetcher.schedule(cid, taskId);
    }

    private static void clearUnreadAsync(String cid) {
        UnreadClearer.start(cid);
    }

    private static String optionText(boolean enabled) {
        return enabled ? "开" : "关";
    }

    static synchronized void finish(long taskId) {
        if (!isActive(taskId)) return;
        running = false;
        status = STATUS_COMPLETED;
        String msg = "加群完成，共处理 " + joinedCount + " 个群（新加入 "
                + newlyJoinedCount + " 个，已加过 " + alreadyJoinedCount + " 个）";
        statusDetail = "已完成 " + joinedCount + "/" + target;
        statusExpiresAt = android.os.SystemClock.elapsedRealtime() + TERMINAL_STATUS_VISIBLE_MS;
        summary("[完成] " + msg);
        summary(buildStatistics());
        Toaster.show("✅ " + msg);
        log(msg);
        notifyStatusChanged();
    }

    public static long getActiveTaskId() {
        return activeTaskId;
    }

    public static boolean isActive(long taskId) {
        return running && taskId == activeTaskId;
    }

    static synchronized void setCurrentGroupName(long taskId, String name) {
        if (!isActive(taskId) || name == null || name.isEmpty()) return;
        lastGroupName = name;
        notifyStatusChanged();
    }

    public static void addStatusListener(StatusListener listener) {
        if (listener == null) return;
        statusListeners.add(listener);
        dispatchStatus(listener, getStatusSnapshot());
    }

    public static void removeStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
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
                status, joinedCount, target, lastGroupName, statusDetail, remainingMs);
    }

    private static void notifyStatusChanged() {
        StatusSnapshot snapshot = getStatusSnapshot();
        for (StatusListener listener : statusListeners) {
            dispatchStatus(listener, snapshot);
        }
    }

    private static void dispatchStatus(StatusListener listener, StatusSnapshot snapshot) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> listener.onStatusChanged(snapshot));
        } catch (Throwable ignored) {}
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

        StatusSnapshot(int status, int completed, int target, String groupName, String detail,
                       long remainingMs) {
            this.status = status;
            this.completed = completed;
            this.target = target;
            this.groupName = groupName;
            this.detail = detail;
            this.remainingMs = remainingMs;
        }
    }

    static void log(String msg) {
        try { FileLogger.i(FileLogger.CAT_SYSTEM, TAG, msg); } catch (Exception ignored) {}
    }

    static void summary(String msg) {
        try { FileLogger.write(FileLogger.CAT_SUMMARY, msg); } catch (Exception ignored) {}
    }

    private static String recordFailure(String message) {
        String text = message == null ? "" : message;
        if (text.contains("没有置顶卡片")) {
            noTopCount++;
            return "无置顶";
        }
        if (text.contains("二维码已过期")
                || text.contains("邀请码已过期")
                || text.contains("链接失效")
                || text.contains("没有下一群链接")) {
            invalidLinkCount++;
            return "链接失效";
        }
        if (text.contains("需要管理员审批")
                || text.contains("需要审批")
                || text.contains("ONLY_MASTER")
                || text.contains("入群申请")) {
            approvalCount++;
            return "需要审批";
        }
        requestFailedCount++;
        return "请求失败";
    }

    private static String currentSubject() {
        if (lastGroupName != null && !lastGroupName.isEmpty()) return lastGroupName;
        if (currentCid != null && !currentCid.isEmpty()) return "cid=" + currentCid;
        return "当前群";
    }

    private static String buildStatistics() {
        int total = newlyJoinedCount + alreadyJoinedCount + noTopCount
                + invalidLinkCount + approvalCount + requestFailedCount;
        return "[统计] 成功 " + newlyJoinedCount
                + "｜已加过 " + alreadyJoinedCount
                + "｜无置顶 " + noTopCount
                + "｜链接失效 " + invalidLinkCount
                + "｜需要审批 " + approvalCount
                + "｜请求失败 " + requestFailedCount
                + "｜合计 " + total + "/" + target;
    }

    static String extractParam(String url, String key) {
        if (url == null) return null;
        String search = key + "=";
        int start = url.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }
}
