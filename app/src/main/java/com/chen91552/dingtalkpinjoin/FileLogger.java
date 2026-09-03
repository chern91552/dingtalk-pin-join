package com.chen91552.dingtalkpinjoin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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

    /** 日志保留天数（含当天），早于此的旧日志在 init 时清理。 */
    private static final int RETAIN_DAYS = 7;

    /** 作者与授权声明，写在每个新日志文件开头。 */
    private static final String AUTHOR_URL = "https://github.com/chern91552/dingtalk-pin-join";
    private static final String[] BANNER = {
            "==================================================",
            " 作者 / 项目地址: " + AUTHOR_URL,
            " 完全免费，禁止倒卖。使用相同代码需标注原作者。",
            "==================================================",
    };

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

        // 清理超过保留天数的日志文件（保留近 RETAIN_DAYS 天，含今天）
        try {
            String cutoff = DATE_FMT.format(
                    new Date(System.currentTimeMillis() - (RETAIN_DAYS - 1L) * 86400000L));
            File[] files = d.listFiles((f, name) ->
                    name.startsWith(NAME_SUMMARY + "_") || name.startsWith(NAME_SYSTEM + "_"));
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    int idx = name.indexOf('_');
                    if (idx >= 0) {
                        String fileDate = name.substring(idx + 1, Math.min(idx + 9, name.length()));
                        // 文件名日期为 yyyyMMdd，可按字符串直接比较；早于 cutoff 的删除
                        if (fileDate.length() == 8 && fileDate.compareTo(cutoff) < 0) {
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
            File f = new File(logDir, name + "_" + date + ".log");
            boolean fresh = !f.exists() || f.length() == 0;
            FileWriter w = new FileWriter(f, true);
            if (fresh) {
                for (String line : BANNER) {
                    w.write(line + "\n");
                }
                w.flush();
            }
            return w;
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

    /**
     * 返回指定分类已有日志的日期，按新到旧排序。
     */
    public static synchronized List<String> listAvailableDates(int cat) {
        List<String> dates = new ArrayList<>();
        if (logDir == null) return dates;

        String prefix = categoryName(cat) + "_";
        File[] files = new File(logDir).listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".log"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String date = name.substring(prefix.length(), name.length() - 4);
                if (date.matches("\\d{8}")) dates.add(date);
            }
        }
        Collections.sort(dates, Collections.reverseOrder());
        return dates;
    }

    /**
     * 读取所选日期的完整日志。
     */
    public static synchronized String readAll(int cat, String date) {
        if (logDir == null || date == null || !date.matches("\\d{8}")) return "";

        File file = new File(logDir, categoryName(cat) + "_" + date + ".log");
        if (!file.isFile()) return "";

        StringBuilder content = new StringBuilder((int) Math.min(file.length(), 1024 * 1024));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                content.append(buffer, 0, count);
            }
            return content.toString();
        } catch (Exception e) {
            return "读取日志失败：" + e.getMessage();
        }
    }

    private static String categoryName(int cat) {
        return cat == CAT_SUMMARY ? NAME_SUMMARY : NAME_SYSTEM;
    }
}
