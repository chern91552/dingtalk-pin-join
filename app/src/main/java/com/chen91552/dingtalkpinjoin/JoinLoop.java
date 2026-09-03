package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;

import de.robv.android.xposed.XposedHelpers;

/**
 * 加群循环状态机
 * <p>
 * 实现连续加群的核心逻辑：点击卡片 → watchdog 检测进群 → 下一跳。
 * 带 500ms 轮询 watchdog、触摸落空自动补点、过期/死链自动停止。
 */
public class JoinLoop implements Runnable, View.OnClickListener, DialogInterface.OnClickListener {

    private static final String TAG = "JOIN_LOOP";
    private static final int MODE_CONTINUE = 1;   // 续跳/重试：实时读 topActivity 后 startHop
    private static final int MODE_DIALOG = 2;
    private static final int MODE_START_HOP = 3;  // 对话框确认后的首跳
    private static final int MODE_WATCHDOG = 4;

    // 时序常量（严格对齐已验证的 dist_skip_6c 版）
    private static final long FIRST_HOP_DELAY_MS = 400;   // 对话框确认后首跳延迟
    private static final long NEXT_HOP_DELAY_MS = 500;    // 进群后下一跳延迟（渲染沉降）
    private static final long CARD_RETRY_MS = 700;        // 卡片未就绪重试间隔
    private static final long WATCHDOG_INTERVAL_MS = 500; // watchdog 轮询间隔
    private static final long HOP_DEADLINE_MS = 20_000;   // 单跳总预算（tapAt+20s）
    private static final long TAP_NO_RESPONSE_MS = 1_000; // 触摸无反应阈值，超过则重点
    private static final int MAX_HOP_RETRY = 7;           // 卡片未就绪重试上限（7×700ms≈5s）

    // ---- 全局状态 ----
    public static volatile boolean running;
    public static volatile boolean advanced;
    public static volatile int remaining;
    public static volatile int totalJoined;

    // ---- 当前跳状态 ----
    static volatile long tapAt;
    static volatile long confirmSeenAt;
    static volatile long hopDeadline;
    static volatile String hopFromCid;
    static volatile int hopGen;
    static volatile int hopRetry;

    // ---- 实例字段 ----
    private final Activity act;
    private final String cid;
    private final int mode;
    private EditText et;
    private int genAt;

    public JoinLoop(Activity act, String cid) {
        this.act = act;
        this.cid = cid;
        this.mode = 0;
    }

    private JoinLoop(Activity act, String cid, int mode) {
        this.act = act;
        this.cid = cid;
        this.mode = mode;
    }

    // ==================== View.OnClickListener（点按钮 → 弹对话框） ====================

    @Override
    public void onClick(View v) {
        try {
            if (running || SilentJoin.running || ForestAudit.isRunning()) {
                Toaster.show("已有任务正在进行，请先停止");
                return;
            }
            EditText input = new EditText(act);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(savedCount(act)));
            input.selectAll();

            JoinLoop dialogHandler = new JoinLoop(act, cid, MODE_DIALOG);
            dialogHandler.et = input;

            new AlertDialog.Builder(act)
                    .setTitle("🔁 从本群开始加群")
                    .setMessage("输入要连续加入的群数量")
                    .setView(input)
                    .setPositiveButton("开始", dialogHandler)
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            FileLogger.i(5, TAG, "dialog ERR " + e);
        }
    }

    // ==================== DialogInterface.OnClickListener（点"开始"） ====================

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (et == null) return;
        if (running || SilentJoin.running || ForestAudit.isRunning()) {
            Toaster.show("已有任务正在进行，请先停止");
            return;
        }

        int count = 10;
        try {
            count = Integer.parseInt(et.getText().toString().trim());
            if (count < 1) count = 1;
        } catch (NumberFormatException ignored) {}

        saveCount(act, count);
        remaining = count;
        totalJoined = 0;
        running = true;
        AutoConfirm.armed = true;

        FileLogger.i(5, TAG, "loop start N=" + count + " from cid=" + cid);
        Toaster.show("🔁 开始加群，共 " + count + " 个");
        otherLog("开始加群，共 " + count + " 个");

        act.finish();

        // 延迟 400ms 后开始首跳（关闭设置页回到聊天页）
        new Handler(Looper.getMainLooper()).postDelayed(
                new JoinLoop(act, cid, MODE_START_HOP), FIRST_HOP_DELAY_MS);
    }

    // ==================== Runnable（状态机调度） ====================

    @Override
    public void run() {
        if (!running) return;

        if (mode == MODE_WATCHDOG) {
            watchdog();
            return;
        }
        // mode 1（续跳/重试）与 mode 3（首跳）：实时读取 topActivity 后点卡片。
        // 续跳必须读"实时"topActivity——onJoined 在确认页触发时新群 ChatMsgActivity 尚未
        // resume，快照会是旧的；等 500ms 后这里执行时新聊天页已 resume。
        Activity top = MainHook.topActivity;
        if (top == null) return;

        // 显式保险：加群确认页仍在前台时（点确认后按钮转圈、RPC 还没回来），绝对不能点
        // 卡片——否则会在原群上再点一次同一个链接，重新弹出同一个确认页。等确认页 finish、
        // 新群聊天页 resume 后，下一轮重试自然在新群上找到下一车卡片。
        if (top.getClass().getName().contains("JoinGroupConfirmActivity")) {
            FileLogger.i(5, TAG, "run: confirm page still in foreground, retry in 700ms");
            new Handler(Looper.getMainLooper()).postDelayed(
                    new JoinLoop(top, cid, MODE_CONTINUE), CARD_RETRY_MS);
            return;
        }

        startHop(top, cid);
    }

    // ==================== 开始一跳 ====================

    static void startHop(Activity act, String cid) {
        FileLogger.i(5, TAG, "startHop: tapping next-car card");

        int result = CardTapper.tap(act);

        if (result != 1) {
            // 卡片没准备好，重试（mode 1：重试时实时读 topActivity）
            hopRetry++;
            if (hopRetry > MAX_HOP_RETRY) {
                stop("最后一个群没有置顶卡片");
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(
                    new JoinLoop(act, cid, MODE_CONTINUE), CARD_RETRY_MS);
            return;
        }

        // 点击成功，架设 watchdog
        hopRetry = 0;
        hopGen++;
        advanced = false;
        tapAt = SystemClock.uptimeMillis();
        confirmSeenAt = 0;

        if (hopDeadline == 0) {
            hopDeadline = tapAt + HOP_DEADLINE_MS;
        }

        hopFromCid = MainHook.curCid;

        JoinLoop wd = new JoinLoop(act, cid, MODE_WATCHDOG);
        wd.genAt = hopGen;
        new Handler(Looper.getMainLooper()).postDelayed(wd, WATCHDOG_INTERVAL_MS);
    }

    // ==================== Watchdog（每 500ms 轮询） ====================

    void watchdog() {
        // 代际检查：如果这期间有新的 hop 开始，本 watchdog 作废
        if (genAt != hopGen) return;
        if (!running) return;

        // 已由 advanceFrom 推进，干等
        if (advanced) {
            FileLogger.i(5, TAG, "watchdog: hop already advanced (fresh join); nothing to do");
            return;
        }

        String fromCid = hopFromCid;
        String nowCid = MainHook.curCid;

        FileLogger.i(5, TAG, "watchdog: hopFromCid=" + fromCid + " curCid=" + nowCid);

        // 进群了（cid 变化）
        if (nowCid != null && (fromCid == null || !fromCid.equals(nowCid))) {
            FileLogger.i(5, TAG, "watchdog: foreground group changed -> entered new group");
            onJoined(nowCid);
            return;
        }

        long now = SystemClock.uptimeMillis();

        // 超时（死链）
        if (now >= hopDeadline) {
            stop("链接无效或群不可搜索");
            return;
        }

        // 确认页已弹 → 交给 AutoConfirm
        if (confirmSeenAt > tapAt) {
            // 继续轮询
            scheduleNext();
            return;
        }

        // 触摸无反应超过阈值 → 重新 tap（对齐验证版：用 hopFromCid）
        if (now - tapAt > TAP_NO_RESPONSE_MS) {
            FileLogger.i(5, TAG, "watchdog: tap produced no navigation after 1s (no confirm page); re-tapping card");
            Activity top = MainHook.topActivity;
            if (top != null) {
                startHop(top, hopFromCid);
            }
            return;
        }

        scheduleNext();
    }

    private void scheduleNext() {
        JoinLoop wd = new JoinLoop(act, cid, MODE_WATCHDOG);
        wd.genAt = genAt;
        new Handler(Looper.getMainLooper()).postDelayed(wd, WATCHDOG_INTERVAL_MS);
    }

    // ==================== 进群回调 ====================

    static void onJoined(String newCid) {
        if (!running) return;

        advanced = true;
        hopDeadline = 0;
        remaining--;
        totalJoined++;

        FileLogger.i(5, TAG, "joined cid=" + newCid + " remaining=" + remaining);

        String name = MainHook.curName != null ? MainHook.curName : newCid;
        otherLog("已加入群：" + name + "（第 " + totalJoined + " 个，剩 " + remaining + " 个）");

        // 每 5 个群弹一次 Toast
        if (totalJoined % 5 == 0) {
            Toaster.show("✅ 已加入第 " + totalJoined + " 个群，剩 " + remaining + " 个");
        }

        // 完成
        if (remaining <= 0) {
            running = false;
            AutoConfirm.armed = false;
            FileLogger.i(5, TAG, "loop finished; no more groups to join");
            otherLog("加群完成，共加入 " + totalJoined + " 个群");
            Toaster.show("✅ 加群完成");
            return;
        }

        // 下一跳：固定延迟 500ms 后从"实时"topActivity 点下一张卡片。
        // 这里不等 curCid——onJoined 本身就是"已进群"信号（newCid 已由确认页 c0 或
        // watchdog 确定）。500ms 是让新群聊天页 + OneBox 卡片渲染出"下一车"的沉降时间；
        // 若卡片没就绪，startHop 会每 700ms 重试；若触摸落空，watchdog 1s 后补点。
        Activity top = MainHook.topActivity;
        if (top == null) {
            running = false;
            FileLogger.i(5, TAG, "no topActivity to continue loop; stopping");
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(
                new JoinLoop(top, newCid, MODE_CONTINUE), NEXT_HOP_DELAY_MS);
    }

    /**
     * 从确认页推进（由 AutoConfirm 调用）
     */
    public static void advanceFrom(Activity confirmAct) {
        if (!running || confirmAct == null) return;

        try {
            Object conv = XposedHelpers.getObjectField(confirmAct, "c0");
            if (conv == null) {
                FileLogger.i(5, TAG, "advanceFrom: c0 null, cannot get joined cid");
                return;
            }
            String newCid = (String) XposedHelpers.callMethod(conv, "conversationId");
            String newName = (String) XposedHelpers.callMethod(conv, "title");
            MainHook.curName = newName;
            onJoined(newCid);
        } catch (Exception e) {
            FileLogger.i(5, TAG, "advanceFrom ERR " + e);
        }
    }

    // ==================== 停止 ====================

    public static void stop(String reason) {
        running = false;
        AutoConfirm.armed = false;
        FileLogger.i(5, TAG, "loop stopped: " + reason);
        Toaster.show("⏹ 加群结束：" + reason);
        otherLog("加群结束：" + reason + "，已加入 " + totalJoined + " 个群");
    }

    // ==================== 持久化 ====================

    static void saveCount(Context ctx, int count) {
        try {
            ctx.getSharedPreferences("esjoin", 0).edit().putInt("cnt", count).apply();
        } catch (Exception ignored) {}
    }

    static int savedCount(Context ctx) {
        try {
            return ctx.getSharedPreferences("esjoin", 0).getInt("cnt", 10);
        } catch (Exception ignored) {
            return 10;
        }
    }

    // ==================== 摘要日志 ====================

    static void otherLog(String msg) {
        try {
            FileLogger.write(2, msg);
        } catch (Exception ignored) {}
    }
}
