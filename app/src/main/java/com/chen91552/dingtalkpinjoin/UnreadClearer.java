package com.chen91552.dingtalkpinjoin;

import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * 纯后台「去除新会话」处理器 —— 零 Activity，对应用户明确硬约束。
 * <p>
 * 正确执行顺序（解决冷启动回归的关键）：
 *   1. 红点/最新消息下发（latest != null → viewMessage；否则红点 > 0 → viewWithoutLastMsg(IM)）
 *   2. 读 {@code Conversation.privateExtension("firstJoin") == "1"} →
 *      立刻 {@code updatePrivateExtension("firstJoin","0")} 走宿主原生 k1 任务写 DB JSON
 *      （必须在第 3 步 Map.remove 之前做，否则读出来的是 null，永远不命中写 DB 分支）
 *   3. 反射 mPrivateExtension Map.remove("firstJoin") —— 即时 UI 生效
 *   4. {@code ed4.b0(Conversation)} —— 强制刷新会话列表
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class UnreadClearer {

    private static final String PREFIX = "[UNREAD] ";

    // ==================== 对外入口（SilentJoin.clearUnreadAsync 已同步改到这里） ====================

    public static void start(final String cid) {
        new Thread(() -> run(cid), "unread-" + cid).start();
    }

    // ==================== 后台 runner：先等 Conversation 就绪，再多趟延迟清除 ====================

    private static void run(final String cid) {
        // 阶段 A：等待 Conversation 进内存（最多 6 次 × 500ms）
        Object conv = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            conv = getConvFromMemory(cid, attempt);
            if (conv != null) break;
            try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
        }
        if (conv == null) {
            log("start timeout cid=" + cid);
            return;
        }

        // 阶段 B：多趟清除（立即 + 间隔 1500ms 再补 2 趟）。
        //   加入新群后钉钉会异步下发一次会话数据（带 firstJoin=1 + 未读），
        //   单次清除若早于该下发会被"复活"。队尾群清完即无后续动作兜底，
        //   故用幂等的延迟补清覆盖晚到下发，专门保护最后一个群。
        clear(conv);
        for (int pass = 0; pass < 2; pass++) {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { return; }
            Object again = getConvFromMemory(cid, -1);
            if (again != null) clear(again);
        }
    }

    /** 从内存取 Conversation；取不到返回 null。attempt < 0 时不打错误日志（补清趟）。 */
    private static Object getConvFromMemory(String cid, int attempt) {
        try {
            ClassLoader cl = MainHook.getClassLoader();
            Class<?> svcCls = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.ConversationService", cl);
            Class<?> imEngine = XposedHelpers.findClass(
                    "com.alibaba.wukong.im.IMEngine", cl);
            Object svc = XposedHelpers.callStaticMethod(imEngine, "getIMService", svcCls);
            return XposedHelpers.callMethod(svc, "getConversationFromMemory", cid);
        } catch (Throwable t) {
            if (attempt >= 0) log("runner attempt " + attempt + " error: " + t);
            return null;
        }
    }

    // ==================== 核心 clear 四阶段（1:1 对应 smali 版 UnreadClearer.smali） ====================

    private static boolean clear(Object conv) {
        if (conv == null) return false;
        try {
            // --- 阶段 1：红点 / 最新消息下发 ---
            int redPoint = (Integer) XposedHelpers.callMethod(conv,
                    "getUserDefineRedPointNumber");
            Object latest = XposedHelpers.callMethod(conv, "latestMessage");
            log("state redPoint=" + redPoint + " latest=" + latest);

            if (latest != null) {
                XposedHelpers.callMethod(latest, "viewMessage");
                log("viewMessage dispatched");
            } else if (redPoint > 0) {
                ClassLoader cl = MainHook.getClassLoader();
                Class<?> bizCls = XposedHelpers.findClass(
                        "com.alibaba.wukong.im.message.MessageViewEnum$VIEW_BIZ", cl);
                Object imEnum = XposedHelpers.getStaticObjectField(bizCls, "IM");
                XposedHelpers.callMethod(conv, "viewWithoutLastMsg", imEnum);
                log("viewWithoutLastMsg(IM) dispatched");
            }

            // --- 阶段 2：先读内存值 → 若 == "1" 立刻 updatePrivateExtension 写 DB（防冷启动回归） ---
            Object cur = XposedHelpers.callMethod(conv, "privateExtension", "firstJoin");
            String curStr = (cur instanceof String) ? (String) cur : null;
            if ("1".equals(curStr)) {
                XposedHelpers.callMethod(conv, "updatePrivateExtension", "firstJoin", "0");
                log("firstJoin DB value updated to 0");
            }

            // --- 阶段 3：再从内存 Map 中 remove firstJoin key（双重保险，即使宿主值还在也即时不命中） ---
            Object mapObj = XposedHelpers.getObjectField(conv, "mPrivateExtension");
            if (mapObj instanceof Map) {
                Map<String, String> map = (Map<String, String>) mapObj;
                if (map.containsKey("firstJoin")) {
                    map.remove("firstJoin");
                    log("firstJoin removed from mPrivateExtension");
                }
            }

            // --- 阶段 4：ed4.b0(Conversation) 强制刷新会话列表 ---
            {
                ClassLoader cl = MainHook.getClassLoader();
                Class<?> ed4 = XposedHelpers.findClass("ed4", cl);
                Class<?> conIface = XposedHelpers.findClass(
                        "com.alibaba.wukong.im.Conversation", cl);
                XposedHelpers.callStaticMethod(ed4, "b0", conIface.cast(conv));
                log("ed4.b0 refresh dispatched");
            }
            return true;
        } catch (Throwable t) {
            log("clear error: " + t);
            return false;
        }
    }

    // ==================== 日志（只写文件，带 [UNREAD] 前缀，对齐 smali 版验收 tag） ====================

    private static void log(String msg) {
        try { FileLogger.write(FileLogger.CAT_SYSTEM, PREFIX + msg); }
        catch (Throwable ignored) {}
    }
}
