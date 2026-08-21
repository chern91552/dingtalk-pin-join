package com.chen91552.dingtalkpinjoin;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志系统
 * <p>
 * 两个分类：
 * 2 = summary（摘要日志：开始/每个群/完成/停止原因，只写文件不打 logcat），
 * 5 = system（详细调试日志）
 * <p>
 * 日志文件按日期切分，自动清理非当日文件。
 */
public class FileLogger {

    // 日志分类
    public static final int CAT_SUMMARY = 2;
    public static final int CAT_SYSTEM = 5;

    private static final String NAME_SUMMARY = "summary";
    private static final String NAME_SYSTEM = "system";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private static String logDir;
    private static String currentDate;
    private static FileWriter summaryWriter;
    private static FileWriter systemWriter;

    /**
     * 初始化日志系统
     * @param dir 日志目录路径
     */
    public static synchronized void init(String dir) {
        logDir = dir;
        File d = new File(dir);
        if (!d.exists()) d.mkdirs();

        // 清理非当日日志文件
        try {
            String today = DATE_FMT.format(new Date());
            File[] files = d.listFiles((f, name) ->
                    name.startsWith(NAME_SUMMARY + "_") || name.startsWith(NAME_SYSTEM + "_"));
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    int idx = name.indexOf('_');
                    if (idx >= 0) {
                        String fileDate = name.substring(idx + 1, Math.min(idx + 9, name.length()));
                        if (!fileDate.equals(today)) {
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        openWriters(DATE_FMT.format(new Date()));
    }

    private static synchronized void openWriters(String date) {
        closeAll();
        currentDate = date;
        summaryWriter = openWriter(NAME_SUMMARY, date);
        systemWriter = openWriter(NAME_SYSTEM, date);
    }

    private static FileWriter openWriter(String name, String date) {
        try {
            return new FileWriter(new File(logDir, name + "_" + date + ".log"), true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static synchronized void closeAll() {
        summaryWriter = closeWriter(summaryWriter);
        systemWriter = closeWriter(systemWriter);
    }

    private static FileWriter closeWriter(FileWriter w) {
        if (w != null) {
            try { w.flush(); } catch (Exception ignored) {}
            try { w.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 写入日志（仅写文件，不输出 logcat）
     */
    public static synchronized void write(int cat, String msg) {
        if (logDir == null) return;

        try {
            // 日期切换
            String today = DATE_FMT.format(new Date());
            if (!today.equals(currentDate)) {
                openWriters(today);
            }

            FileWriter w = (cat == CAT_SUMMARY) ? summaryWriter : systemWriter;
            if (w == null) return;

            String time = TIME_FMT.format(new Date());
            w.write(time + "  " + msg + "\n");
            w.flush();
        } catch (Exception ignored) {}
    }

    /**
     * 写入日志并输出到 logcat（info 级别）
     */
    public static void i(int cat, String tag, String msg) {
        android.util.Log.i(tag, msg);
        write(cat, msg);
    }

    /**
     * 写入日志并输出到 logcat（error 级别）
     */
    public static void e(int cat, String tag, String msg) {
        android.util.Log.e(tag, msg);
        write(cat, msg);
    }
}
