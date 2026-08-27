package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.View;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

/**
 * 提取本群邀请链接 —— 通过钉钉 ConversationService RPC 获取群邀请链接并复制到剪贴板。
 */
public class ProbeLink implements View.OnClickListener, Runnable {

    private static final String TAG = "PROBE_LINK";

    private final Activity act;
    private final String cid;

    public ProbeLink(Activity act) {
        this.act = act;
        this.cid = null;
    }

    public ProbeLink(Activity act, String cid) {
        this.act = act;
        this.cid = cid;
    }

    @Override
    public void onClick(View v) {
        android.util.Log.i("PinJoin", "ProbeLink onClick fired");
        new Thread(this).start();
    }

    @Override
    public void run() {
        dump(act, cid);
    }

    /**
     * 提取群邀请链接并复制到剪贴板
     */
    public static void dump(Activity act, String cid) {
        if (cid == null || cid.isEmpty()) {
            cid = getCidFromIntent(act);
            if (cid.isEmpty()) {
                FileLogger.i(5, TAG, "no cid (intent + arg both empty)");
                return;
            }
        }

        try {
            // 通过 ConversationService RPC 获取邀请链接
            String link = getGroupInviteLink(cid);

            FileLogger.i(5, TAG, "cid=" + cid + " groupLink=" + link);

            if (link != null) {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(
                        Activity.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("group link", link));
                Toaster.show("本群邀请链接已复制：" + link);
            }
        } catch (Exception e) {
            FileLogger.i(5, TAG, "ERR " + e);
        }
    }

    /**
     * 从 Activity Intent 中提取 cid
     */
    private static String getCidFromIntent(Activity act) {
        try {
            String cid = act.getIntent().getStringExtra("conversation_id");
            if (cid != null && !cid.isEmpty()) return cid;
            cid = act.getIntent().getStringExtra("cid");
            return cid != null ? cid : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 通过钉钉内部 RPC 获取群邀请链接
     * <p>
     * 调用 ConversationService.getCode(cid) → 返回群邀请码/链接。
     * 注意：此方法返回的是当前群自己的邀请链接，不是下一车链接。
     */
    private static String getGroupInviteLink(String cid) {
        ClassLoader cl = MainHook.getClassLoader();
        if (cl == null) return null;

        try {
            android.util.Log.i("PinJoin", "getGroupInviteLink start, cid=" + cid);

            // 1. 获取 IMEngine
            Class<?> imEngineCls = cl.loadClass("com.alibaba.wukong.im.IMEngine");
            android.util.Log.i("PinJoin", "imEngineCls ok");

            // 2. 获取 ConversationService 实例
            Class<?> convServiceCls = cl.loadClass("com.alibaba.wukong.im.ConversationService");
            Method getService = imEngineCls.getMethod("getIMService", Class.class);
            Object convService = getService.invoke(null, convServiceCls);
            android.util.Log.i("PinJoin", "convService=" + convService);

            if (convService == null) return null;

            // 3. 同步等待回调
            final String[] result = new String[1];
            final Object lock = new Object();

            // 4. 创建回调 Proxy (com.alibaba.wukong.Callback)
            Class<?> callbackCls = cl.loadClass("com.alibaba.wukong.Callback");
            android.util.Log.i("PinJoin", "callbackCls=" + callbackCls.getName() + " methods=" + callbackCls.getDeclaredMethods().length);
            for (Method m : callbackCls.getDeclaredMethods()) {
                android.util.Log.i("PinJoin", "  callback method: " + m.getName());
            }
            
            Object callback = Proxy.newProxyInstance(cl, new Class[]{callbackCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            android.util.Log.i("PinJoin", "callback invoke: " + name + " args=" + (args != null ? args.length : 0));
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
                            if ("toString".equals(name)) return "PinJoinLinkCallback";
                            if (args != null && args.length > 0 && args[0] != null) {
                                Object obj = args[0];
                                android.util.Log.i("PinJoin", "callback obj type=" + obj.getClass().getName());
                                // 列出所有方法
                                for (Method m : obj.getClass().getDeclaredMethods()) {
                                    android.util.Log.i("PinJoin", "  method: " + m.getName() + " returns " + m.getReturnType().getName());
                                }
                                // 尝试提取 URL：优先 getShortLink → getOriginalLink → getCode
                                String url = null;
                                try { url = (String) obj.getClass().getMethod("getShortLink").invoke(obj); } catch (Exception ignored) {}
                                if (url == null) try { url = (String) obj.getClass().getMethod("getOriginalLink").invoke(obj); } catch (Exception ignored) {}
                                if (url == null) try { url = (String) obj.getClass().getMethod("getCode").invoke(obj); } catch (Exception ignored) {}
                                if (url == null) url = obj.toString();
                                result[0] = url;
                                android.util.Log.i("PinJoin", "callback result: " + url);
                            }
                            synchronized (lock) { lock.notify(); }
                            return null;
                        }
                    });
            android.util.Log.i("PinJoin", "callback created");

            // 5. 找到 getCode 方法并调用
            Method getCode = null;
            for (Method m : convServiceCls.getDeclaredMethods()) {
                if (m.getName().equals("getCode") && m.getParameterTypes().length == 2) {
                    getCode = m;
                    android.util.Log.i("PinJoin", "getCode found: " + m.getParameterTypes()[0].getName() + ", " + m.getParameterTypes()[1].getName());
                    break;
                }
            }
            if (getCode == null) {
                android.util.Log.e("PinJoin", "getCode method not found");
                return null;
            }

            getCode.setAccessible(true);
            getCode.invoke(convService, callback, cid);
            android.util.Log.i("PinJoin", "getCode invoked, waiting...");

            // 6. 等待结果（10s 超时）
            synchronized (lock) { lock.wait(10000); }

            android.util.Log.i("PinJoin", "getGroupInviteLink result=" + result[0]);
            return result[0];
        } catch (Exception e) {
            android.util.Log.e("PinJoin", "getGroupInviteLink ERR: " + e.getMessage(), e);
            return null;
        }
    }
}
