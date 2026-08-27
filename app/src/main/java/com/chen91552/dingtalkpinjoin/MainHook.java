package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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

        // Hook rwm.h() —— 卡片渲染时捕获 nexturl（静默加群用）
        try {
            Class<?> rwm = XposedHelpers.findClass("rwm", cl);
            XposedBridge.hookAllMethods(rwm, "h", new DurboProbe());
            android.util.Log.i(TAG, "hook rwm.h OK");
        } catch (Throwable e) {
            android.util.Log.e(TAG, "hook rwm.h FAIL: " + e);
        }
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

            // 水平包裹容器
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            int dp8 = dp(act, 8);
            row.setPadding(dp8, dp8, dp8, dp8);

            View btnJoin = buildGridCell(act, "🔁\n从本群加群", "连续点击置顶卡片", dark);
            btnJoin.setOnClickListener(new JoinLoop(act, cid));
            row.addView(btnJoin);

            View btnSilent = buildGridCell(act, "🔇\n静默加群", "后台连续加群不跳页", dark);
            btnSilent.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSilentJoinDialog(act, cid);
                }
            });
            row.addView(btnSilent);

            View btnLink = buildGridCell(act, "🔗\n提取本群链接", "复制邀请链接到剪贴板", dark);
            btnLink.setOnClickListener(new ProbeLink(act, cid));
            row.addView(btnLink);

            container.addView(row);
        } catch (Exception e) {
            android.util.Log.e("PinJoin", "inject ERR: " + e.getMessage());
        }
    }

    // ==================== 静默加群弹窗 ====================

    private void showSilentJoinDialog(final Activity act, final String cid) {
        try {
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
                        SilentJoin.start(cid, n, cb.isChecked());
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            android.util.Log.e("PinJoin", "silent dialog ERR: " + e);
        }
    }

    // ==================== UI 工具方法 ====================

    /**
     * 构建网格按钮 Cell
     */
    private LinearLayout buildGridCell(Activity act, String title, String subtitle, boolean dark) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
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
        tvTitle.setTextColor(dark ? 0xFFCCCCCC : 0xFF333333);
        tvTitle.setPadding(0, 0, 0, dp4);
        cell.addView(tvTitle);

        // 副标题
        TextView tvSub = new TextView(act);
        tvSub.setText(subtitle);
        tvSub.setTextSize(10);
        tvSub.setGravity(Gravity.CENTER);
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
