package com.chen91552.dingtalkpinjoin;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

public class SilentJoin {

    private static final String TAG = "SilentJoin";

    public static volatile boolean running;
    public static volatile boolean clearUnread;
    public static volatile int target;
    public static volatile int joinedCount;
    public static volatile int alreadyJoinedCount;
    public static volatile int newlyJoinedCount;
    public static volatile String lastGroupName;

    private static final Set<String> seenCodes = new HashSet<>();
    private static final Set<String> joinedCids = new HashSet<>();

    public static void start(String cid, int count, boolean clearUnreadFlag) {
        running = true;
        target = count;
        clearUnread = clearUnreadFlag;
        joinedCount = 0;
        alreadyJoinedCount = 0;
        newlyJoinedCount = 0;
        seenCodes.clear();

        summary("静默加群开始，共 " + count + " 个");
        Toaster.show("🔇 静默加群开始，共 " + count + " 个");
        log("start cid=" + cid + " N=" + count);

        NextGroupFetcher.schedule(cid);
    }

    public static void onError(String msg) {
        if (!running) return;
        running = false;
        String stat = "已成功处理 " + joinedCount + "/" + target
                + " 个群（新加入 " + newlyJoinedCount + " 个，已加过 " + alreadyJoinedCount + " 个）";
        String err = "加群异常终止：" + msg + "；" + stat;
        log("error: " + msg + " | " + stat);
        summary(err);
        Toaster.show("⚠️ 静默加群异常：" + msg + "（已成功 " + joinedCount + " 个）");
    }

    public static void onNextUrl(String nexturl) {
        if (!running) return;
        try {
            String code = extractParam(nexturl, "code");
            String origin = extractParam(nexturl, "origin");
            if (code == null || code.isEmpty()) return;
            if (!seenCodes.add(code)) return;

            log("nexturl code=" + code);
            verifyCode(code, origin != null ? origin : "11");
        } catch (Throwable t) {
            log("onNextUrl ERR: " + t);
        }
    }

    private static void verifyCode(String code, String origin) {
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

            Object callback = VerifyCallbackProxy.create(cl, code, originInt);
            XposedHelpers.callMethod(svc, "verifyCodeV2", callback, model);
            log("verifyCode sent: " + code);
        } catch (Throwable t) {
            log("verifyCode ERR: " + t);
            onError("验证失败");
        }
    }

    static void joinGroup(String cid, Object uid, int origin, String code) {
        if (!running) return;

        if (!joinedCids.add(cid)) {
            onAlreadyJoined(cid);
            return;
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
                onAlreadyJoined(cid);
                return;
            }
        } catch (Throwable ignored) {}

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

            Object safeCb = SafeCheckCallbackProxy.create(cl, cid, uid, origin, code);
            XposedHelpers.callMethod(safeSvc, "preJoinGroupSafeCheck", safeReq, safeCb);
            log("safeCheck sent cid=" + cid);
        } catch (Throwable t) {
            log("joinGroup ERR: " + t);
            onError("加群失败");
        }
    }

    static void doAddMember(String cid, Object uid, int origin, String token, String code) {
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

            Object handler = JoinCallbackProxy.create(cl, cid);
            XposedHelpers.callMethod(svc, "addGroupMemberByQrcodeV4", req, handler);
            log("addGroupMember sent cid=" + cid);
        } catch (Throwable t) {
            log("doAddMember ERR: " + t);
            onError("加群请求失败");
        }
    }

    static void onJoinOk(String cid) {
        if (!running) return;

        joinedCount++;
        newlyJoinedCount++;
        String name = lastGroupName != null ? lastGroupName : cid;
        summary("✅ 新加入群：" + name + "（第 " + joinedCount + "/" + target + " 个）");
        log("joined " + cid + " count=" + joinedCount + "/" + target);

        if (clearUnread) clearUnreadAsync(cid);

        if (joinedCount % 5 == 0) {
            Toaster.show("✅ 已静默加入第 " + joinedCount + " 个群");
        }
        if (joinedCount >= target) {
            finish();
            return;
        }

        NextGroupFetcher.schedule(cid);
    }

    static void onAlreadyJoined(String cid) {
        if (!running) return;

        joinedCount++;
        alreadyJoinedCount++;
        String name = lastGroupName != null ? lastGroupName : cid;
        summary("⏭️ 已加过该群：" + name + "（跳过，第 " + joinedCount + "/" + target + " 个）");
        log("already joined cid=" + cid + " count=" + joinedCount + "/" + target);

        if (clearUnread) clearUnreadAsync(cid);

        if (joinedCount % 5 == 0) {
            Toaster.show("✅ 已加群第 " + joinedCount + " 个群");
        }

        if (joinedCount >= target) {
            finish();
            return;
        }

        NextGroupFetcher.schedule(cid);
    }

    private static void clearUnreadAsync(String cid) {
        UnreadClearer.start(cid);
    }

    static void finish() {
        running = false;
        String msg = "加群完成，共处理 " + joinedCount + " 个群（新加入 "
                + newlyJoinedCount + " 个，已加过 " + alreadyJoinedCount + " 个）";
        summary(msg);
        Toaster.show("✅ " + msg);
        log(msg);
    }

    static void log(String msg) {
        try { FileLogger.i(FileLogger.CAT_SYSTEM, TAG, msg); } catch (Exception ignored) {}
    }

    static void summary(String msg) {
        try { FileLogger.write(FileLogger.CAT_SUMMARY, msg); } catch (Exception ignored) {}
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
