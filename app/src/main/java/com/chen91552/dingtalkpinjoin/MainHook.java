package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块入口 —— 钉钉置顶加群
 * <p>
 * 在群设置页注入菜单，通过点击群顶部 OneBox 置顶卡片实现自动连续加群。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String DINGTALK_PKG = "com.alibaba.android.rimet";
    private static final String TAG = "PinJoin";

    /** 作者标注（不展示给用户，仅编译进 dex 供溯源）。 */
    @SuppressWarnings("unused")
    private static final String AUTHOR = "https://github.com/chern91552/dingtalk-pin-join";

    // ---- 全局状态 ----
    public static volatile String curCid;
    public static volatile String curName;
    public static volatile Activity topActivity;
    private static ClassLoader hostClassLoader;

    // ==================== Xposed 入口 ====================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            android.util.Log.i(TAG, "==> handleLoadPackage called, pkg=" + lpparam.packageName + " first=" + lpparam.isFirstApplication + " proc=" + lpparam.processName);
            
            // 仅处理钉钉主进程
            if (!DINGTALK_PKG.equals(lpparam.packageName)) return;
            if (!lpparam.isFirstApplication) return;
            if (!isMainProcess(lpparam.processName)) return;

            hostClassLoader = lpparam.classLoader;

            // 初始化文件日志
            FileLogger.init(getLogDir());

            // 注册全局 Activity 生命周期跟踪（可靠更新 topActivity，不依赖 onResume hook）
            registerActivityTracker();

            // 部署所有加群相关 Hook
            deployJoinHooks(lpparam);
        } catch (Throwable e) {
            android.util.Log.e(TAG, "handleLoadPackage FAIL: " + e.getMessage(), e);
        }
    }

    // ==================== Hook 部署 ====================

    private void deployJoinHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        android.util.Log.i(TAG, "deployJoinHooks start");

        try {
            Class<?> joinConfirmCls = XposedHelpers.findClass(
                    "com.alibaba.android.dingtalkim.activities.JoinGroupConfirmActivity", cl);
            XposedBridge.hookAllMethods(joinConfirmCls, "onCreate", new AutoConfirm());
            android.util.Log.i(TAG, "hook JoinGroupConfirmActivity OK");
        } catch (Throwable e) {
            android.util.Log.e(TAG, "hook JoinGroupConfirmActivity FAIL: " + e);
        }

        try {
            Class<?> chatMsgCls = XposedHelpers.findClass(
                    "com.alibaba.android.dingtalkim.activities.ChatMsgActivity", cl);
            XposedBridge.hookAllMethods(chatMsgCls, "onResume", new TopTracker());
            android.util.Log.i(TAG, "hook ChatMsgActivity OK");
        } catch (Throwable e) {
            android.util.Log.e(TAG, "hook ChatMsgActivity FAIL: " + e);
        }

        try {
            Class<?> groupSettingsCls = XposedHelpers.findClass(
                    "com.alibaba.android.dingtalkim.activities.ConversationSettingsActivity", cl);
            XposedBridge.hookAllMethods(groupSettingsCls, "onResume",
                    new SettingsResumeHook(this));
            android.util.Log.i(TAG, "hook ConversationSettingsActivity OK");
        } catch (Throwable e) {
            android.util.Log.e(TAG, "hook ConversationSettingsActivity FAIL: " + e);
        }

        // SilentJoin fetches card data explicitly through NextGroupFetcher.
        // Do not consume passive render callbacks: opening another group while a
        // task is running must not redirect the background chain to that group.
    }

    // ==================== 全局 Activity 跟踪 ====================

    private void registerActivityTracker() {
        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Application app = (Application) param.thisObject;
                        app.registerActivityLifecycleCallbacks(new ActivityTracker());
                        android.util.Log.i(TAG, "ActivityTracker registered");
                    } catch (Throwable e) {
                        android.util.Log.e(TAG, "registerActivityLifecycleCallbacks FAIL: " + e);
                    }
                }
            });
        } catch (Throwable e) {
            android.util.Log.e(TAG, "hook Application.onCreate FAIL: " + e);
        }
    }

    // ==================== 菜单注入 ====================

    /** 供 SettingsResumeHook 回调 */
    public void injectInto(Activity act) {
        injectGroupSettingsButtons(act, act.getClass());
    }

    private void injectGroupSettingsButtons(Activity act, Class<?> cls) {
        try {
            ViewGroup container = findBestContainer(act.getWindow().getDecorView());
            if (container == null) return;
            // 用 tag 防重复注入
            if ("pinjoin_injected".equals(container.getTag())) return;
            container.setTag("pinjoin_injected");

            String cid = getCidFromActivity(act, cls);
            if (cid == null || cid.isEmpty()) return;

            boolean dark = isDarkMode(act);

            int dp8 = dp(act, 8);
            LinearLayout featureRow = new LinearLayout(act);
            featureRow.setOrientation(LinearLayout.HORIZONTAL);
            featureRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            featureRow.setPadding(dp(act, 4), dp(act, 4), dp(act, 4), dp(act, 4));

            View btnJoin = buildGridCell(
                    act, "🔁\n置顶加群", "前台点击卡片\n连续加入群聊", dark);
            btnJoin.setOnClickListener(v -> {
                if (!rejectWhenTaskRunning()) {
                    new JoinLoop(act, cid).onClick(v);
                }
            });
            featureRow.addView(btnJoin);

            View btnSilent = buildGridCell(
                    act, "🔇\n静默加群", "后台静默执行\n连续加入群聊", dark);
            btnSilent.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSilentJoinDialog(act, cid);
                }
            });
            featureRow.addView(btnSilent);

            View btnAudit = buildGridCell(
                    act, "🔎\n置顶查证", "沿置顶逐群查\n公益树和证书", dark);
            btnAudit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showForestAuditDialog(act, cid);
                }
            });
            featureRow.addView(btnAudit);

            View btnLink = buildGridCell(
                    act, "🔗\n提取链接", "提取当前群的\n邀请链接", dark);
            btnLink.setOnClickListener(new ProbeLink(act, cid));
            featureRow.addView(btnLink);

            container.addView(featureRow);

            SilentJoinStatusView statusView = new SilentJoinStatusView(act, dark);
            LinearLayout.LayoutParams statusParams =
                    new LinearLayout.LayoutParams(-1, dp(act, 52));
            statusParams.setMargins(dp8, 0, dp8, dp(act, 4));
            container.addView(statusView.getView(), statusParams);

            ForestAuditStatusView auditStatusView =
                    new ForestAuditStatusView(act, dark);
            LinearLayout.LayoutParams auditStatusParams =
                    new LinearLayout.LayoutParams(-1, dp(act, 52));
            auditStatusParams.setMargins(dp8, 0, dp8, dp(act, 4));
            container.addView(auditStatusView.getView(), auditStatusParams);

            // 页脚：日志入口 + 免费声明 + 项目地址
            TextView footer = new TextView(act);
            String logText = "查看日志";
            String prefix = logText + " · 完全免费 · 禁止倒卖 · ";
            String linkText = "项目地址";
            SpannableString sp = new SpannableString(prefix + linkText);
            final int linkColor = dark ? 0xFF7AA7FF : 0xFF2B6CE6;

            int logStart = 0;
            int logEnd = logStart + logText.length();
            sp.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    new LogViewer(act).onClick(widget);
                }
            }, logStart, logEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(linkColor),
                    logStart, logEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new UnderlineSpan(),
                    logStart, logEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int start = prefix.length();
            int end = start + linkText.length();
            sp.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    try {
                        Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(AUTHOR));
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(it);
                    } catch (Exception ignored) {}
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            footer.setText(sp);
            footer.setMovementMethod(LinkMovementMethod.getInstance());
            footer.setTextSize(11);
            footer.setGravity(Gravity.CENTER);
            footer.setTextColor(dark ? 0xFF9AA0A6 : 0xFF888888);
            int fp = dp(act, 10);
            footer.setPadding(fp, dp(act, 4), fp, fp);
            container.addView(footer);
        } catch (Exception e) {
            android.util.Log.e("PinJoin", "inject ERR: " + e.getMessage());
        }
    }

    // ==================== 静默加群弹窗 ====================

    private void showForestAuditDialog(final Activity act, final String cid) {
        if (rejectWhenTaskRunning()) return;
        final EditText input = new EditText(act);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText("3");
        input.selectAll();
        int pad = dp(act, 20);
        LinearLayout wrapper = new LinearLayout(act);
        wrapper.setPadding(pad, dp(act, 8), pad, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(act)
                .setTitle("置顶查证")
                .setMessage("输入沿置顶链接连续查询的群数量")
                .setView(wrapper)
                .setPositiveButton("开始", (dialog, which) -> {
                    int count = 3;
                    try {
                        count = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable ignored) {}
                    ForestAudit.start(cid, Math.max(1, count));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSilentJoinDialog(final Activity act, final String cid) {
        try {
            if (rejectWhenTaskRunning()) return;
            LinearLayout layout = new LinearLayout(act);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 20);
            layout.setPadding(pad, pad / 2, pad, 0);

            final EditText et = new EditText(act);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setText("3");
            et.selectAll();
            layout.addView(et);

            final CheckBox cb = new CheckBox(act);
            cb.setText("去除新会话标记");
            cb.setChecked(false);
            LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cbp.topMargin = dp(act, 8);
            cb.setLayoutParams(cbp);
            layout.addView(cb);

            final CheckBox mute = new CheckBox(act);
            mute.setText("处理到的群设为消息免打扰");
            mute.setChecked(false);
            layout.addView(mute);

            final CheckBox muteAtAll = new CheckBox(act);
            muteAtAll.setText("@所有人消息不提醒");
            muteAtAll.setChecked(false);
            muteAtAll.setEnabled(false);
            mute.setOnCheckedChangeListener((buttonView, isChecked) -> {
                muteAtAll.setEnabled(isChecked);
                if (!isChecked) muteAtAll.setChecked(false);
            });
            layout.addView(muteAtAll);

            new AlertDialog.Builder(act)
                    .setTitle("🔇 静默循环加群")
                    .setMessage("输入要静默加入的群数量（无需打开页面）")
                    .setView(layout)
                    .setPositiveButton("开始", (dialog, which) -> {
                        int n = 3;
                        try {
                            String text = et.getText().toString().trim();
                            if (!text.isEmpty()) n = Integer.parseInt(text);
                        } catch (Exception ignored) {}
                        if (n < 1) n = 1;
                        SilentJoin.start(
                                cid, n, cb.isChecked(),
                                mute.isChecked(), muteAtAll.isChecked());
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            android.util.Log.e("PinJoin", "silent dialog ERR: " + e);
        }
    }

    private boolean rejectWhenTaskRunning() {
        if (!JoinLoop.running && !SilentJoin.running && !ForestAudit.isRunning()) return false;
        Toaster.show("已有任务正在进行，请先停止");
        return true;
    }

    // ==================== UI 工具方法 ====================

    /**
     * 构建网格按钮 Cell
     */
    private LinearLayout buildGridCell(Activity act, String title, String subtitle, boolean dark) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cellParams =
                new LinearLayout.LayoutParams(0, dp(act, 96), 1f);
        int gap = dp(act, 3);
        cellParams.setMargins(gap, dp(act, 4), gap, dp(act, 4));
        cell.setLayoutParams(cellParams);
        cell.setClickable(true);
        cell.setFocusable(true);

        int dp4 = dp(act, 4);
        int dp8 = dp(act, 8);
        cell.setPadding(dp4, dp8, dp4, dp8);

        // 圆角背景 + 按压态，支持暗黑模式
        cell.setBackground(createCellBg(dp(act, 8), dark));

        // 标题
        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setMaxLines(2);
        tvTitle.setTextColor(dark ? 0xFFCCCCCC : 0xFF333333);
        tvTitle.setPadding(0, 0, 0, dp4);
        cell.addView(tvTitle);

        // 副标题
        TextView tvSub = new TextView(act);
        tvSub.setText(subtitle);
        tvSub.setTextSize(10);
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setMaxLines(2);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        tvSub.setTextColor(dark ? 0xFF888888 : 0xFF999999);
        cell.addView(tvSub);

        return cell;
    }

    private static StateListDrawable createCellBg(int radius, boolean dark) {
        int normal = dark ? 0xFF2A2A2A : 0xFFF5F5F5;
        int pressed = dark ? 0xFF3A3A3A : 0xFFE0E0E0;

        GradientDrawable gdNormal = new GradientDrawable();
        gdNormal.setCornerRadius(radius);
        gdNormal.setColor(normal);

        GradientDrawable gdPressed = new GradientDrawable();
        gdPressed.setCornerRadius(radius);
        gdPressed.setColor(pressed);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, gdPressed);
        sld.addState(new int[]{}, gdNormal);
        return sld;
    }

    /**
     * 遍历 View 树查找最佳容器
     */
    private ViewGroup findBestContainer(View root) {
        if (root == null) return null;
        // 1. 找 RecyclerView → 取它的父容器（LinearLayout）
        ViewGroup rv = findRecyclerView(root);
        if (rv != null && rv.getParent() instanceof ViewGroup) {
            return (ViewGroup) rv.getParent();
        }
        // 2. 找 ScrollView
        ViewGroup sv = findScrollView(root);
        if (sv != null) return sv;
        // 3. 找第一个 LinearLayout
        return findFirstLinearLayout(root);
    }

    private ViewGroup findRecyclerView(View root) {
        if (root == null) return null;
        if (root.getClass().getName().contains("RecyclerView")) return (ViewGroup) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup found = findRecyclerView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private ViewGroup findScrollView(View root) {
        if (root == null) return null;
        if (root.getClass().getName().contains("ScrollView")) return (ViewGroup) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup found = findScrollView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private ViewGroup findFirstLinearLayout(View root) {
        if (root instanceof LinearLayout) return (ViewGroup) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup found = findFirstLinearLayout(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 递归检查 View 树中是否包含指定文本
     */
    private boolean findTextInView(View root, String text) {
        if (root == null) return false;
        try {
            if (root instanceof TextView) {
                CharSequence cs = ((TextView) root).getText();
                if (cs != null && cs.toString().contains(text)) return true;
            }
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    if (findTextInView(vg.getChildAt(i), text)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ==================== 辅助方法 ====================

    private String getCidFromActivity(Activity act, Class<?> cls) {
        try {
            // 方式1: getConversation() → conversationId()
            Object conv = cls.getMethod("getConversation").invoke(act);
            if (conv != null) {
                return (String) conv.getClass().getMethod("conversationId").invoke(conv);
            }
        } catch (Exception ignored) {}

        // 方式2: Intent extras
        try {
            String cid = act.getIntent().getStringExtra("conversation_id");
            if (cid != null && !cid.isEmpty()) return cid;
            cid = act.getIntent().getStringExtra("cid");
            if (cid != null && !cid.isEmpty()) return cid;
        } catch (Exception ignored) {}

        return "";
    }

    private String getNameFromActivity(Activity act, Class<?> cls) {
        try {
            Object conv = cls.getMethod("getConversation").invoke(act);
            if (conv != null) {
                return (String) conv.getClass().getMethod("title").invoke(conv);
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static ClassLoader getClassLoader() {
        return hostClassLoader;
    }

    private static boolean isMainProcess(String processName) {
        return processName == null || !processName.contains(":");
    }

    private static boolean isDarkMode(Activity act) {
        try {
            int nightMode = act.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return false;
        }
    }

    private static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String getLogDir() {
        int uid = android.os.Process.myUid() / 100000;
        // media 公共目录：adb 无 root 可直接 pull，便于验证/排查（与 smali 版 EnergyPaste 分开）
        return "/storage/emulated/0/Android/media/" + DINGTALK_PKG
                + "/PinJoin/" + uid + "/log/";
    }

    private static void log(String msg) {
        try {
            FileLogger.i(5, TAG, msg);
        } catch (Exception ignored) {}
    }
}
