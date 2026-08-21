package com.chen91552.dingtalkpinjoin;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 主线程 Toast 封装 —— 确保 Toast 在 UI 线程显示。
 */
public class Toaster implements Runnable {

    private final String msg;

    public Toaster(String msg) {
        this.msg = msg;
    }

    public static void show(String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Toaster(msg));
        } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        try {
            if (MainHook.topActivity != null) {
                Toast.makeText(MainHook.topActivity, msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }
}