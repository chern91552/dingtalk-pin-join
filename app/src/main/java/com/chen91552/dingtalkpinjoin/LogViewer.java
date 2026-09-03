package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群设置页内的日志查看器。
 */
public final class LogViewer implements View.OnClickListener {

    private static final Pattern SECRET_PATTERN =
            Pattern.compile("(?i)(code|token)=([^\\s&]+)");

    private final Activity activity;
    private final List<Integer> matches = new ArrayList<>();

    private int matchIndex = -1;
    private String logText = "";
    private Spinner dateSpinner;
    private EditText searchInput;
    private TextView matchStatus;
    private TextView contentView;
    private ScrollView contentScroll;

    public LogViewer(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onClick(View ignored) {
        show();
    }

    private void show() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(4), pad, 0);

        LinearLayout dateRow = new LinearLayout(activity);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dateLabel = new TextView(activity);
        dateLabel.setText("日期");
        dateLabel.setPadding(0, 0, dp(8), 0);
        dateRow.addView(dateLabel);

        dateSpinner = new Spinner(activity);
        dateRow.addView(dateSpinner, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(dateRow);

        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        searchInput = new EditText(activity);
        searchInput.setHint("搜索日志");
        searchInput.setSingleLine(true);
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button previous = button("上一个");
        Button next = button("下一个");
        searchRow.addView(previous);
        searchRow.addView(next);
        root.addView(searchRow);

        matchStatus = new TextView(activity);
        matchStatus.setTextSize(11);
        matchStatus.setGravity(Gravity.END);
        root.addView(matchStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        contentView = new TextView(activity);
        contentView.setTextSize(12);
        contentView.setTypeface(Typeface.MONOSPACE);
        contentView.setTextIsSelectable(true);
        contentView.setPadding(dp(8), dp(8), dp(8), dp(8));

        contentScroll = new ScrollView(activity);
        contentScroll.setFillViewport(true);
        contentScroll.addView(contentView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int contentHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
        root.addView(contentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, contentHeight));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button top = button("顶部");
        Button bottom = button("底部");
        Button refresh = button("刷新");
        Button copy = button("复制");
        actions.addView(top, weightedParams());
        actions.addView(bottom, weightedParams());
        actions.addView(refresh, weightedParams());
        actions.addView(copy, weightedParams());
        root.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("运行摘要")
                .setView(root)
                .setNegativeButton("关闭", null)
                .create();

        dateSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(this::loadContent));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                rebuildMatches();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        previous.setOnClickListener(v -> moveMatch(-1));
        next.setOnClickListener(v -> moveMatch(1));
        top.setOnClickListener(v -> contentScroll.fullScroll(View.FOCUS_UP));
        bottom.setOnClickListener(v -> contentScroll.fullScroll(View.FOCUS_DOWN));
        refresh.setOnClickListener(v -> loadContent());
        copy.setOnClickListener(v -> copyContent());

        dialog.setOnShowListener(v -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
        refreshDatesAndContent();
    }

    private void refreshDatesAndContent() {
        List<String> dates = FileLogger.listAvailableDates(FileLogger.CAT_SUMMARY);
        if (dates.isEmpty()) {
            dates.add(new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date()));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, dates);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dateSpinner.setAdapter(adapter);
        loadContent();
    }

    private void loadContent() {
        Object selected = dateSpinner.getSelectedItem();
        if (selected == null) return;

        String raw = FileLogger.readAll(FileLogger.CAT_SUMMARY, selected.toString());
        logText = redactSecrets(raw);
        if (logText.isEmpty()) logText = "当天暂无日志";
        contentView.setText(logText);
        rebuildMatches();
        contentScroll.post(() -> contentScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void rebuildMatches() {
        matches.clear();
        matchIndex = -1;
        String query = searchInput.getText().toString();
        if (!query.isEmpty()) {
            String source = logText.toLowerCase(Locale.getDefault());
            String needle = query.toLowerCase(Locale.getDefault());
            int from = 0;
            while (from < source.length()) {
                int found = source.indexOf(needle, from);
                if (found < 0) break;
                matches.add(found);
                from = found + Math.max(1, needle.length());
            }
        }
        if (!matches.isEmpty()) matchIndex = 0;
        renderMatch();
    }

    private void moveMatch(int direction) {
        if (matches.isEmpty()) return;
        matchIndex = (matchIndex + direction + matches.size()) % matches.size();
        renderMatch();
    }

    private void renderMatch() {
        String query = searchInput.getText().toString();
        if (matchIndex < 0 || matches.isEmpty() || query.isEmpty()) {
            contentView.setText(logText);
            matchStatus.setText(query.isEmpty() ? "" : "未找到");
            return;
        }

        int start = matches.get(matchIndex);
        int end = Math.min(logText.length(), start + query.length());
        SpannableString highlighted = new SpannableString(logText);
        highlighted.setSpan(new BackgroundColorSpan(0xFFFFC107),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        highlighted.setSpan(new ForegroundColorSpan(0xFF000000),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        contentView.setText(highlighted);
        matchStatus.setText((matchIndex + 1) + "/" + matches.size());
        contentView.post(() -> {
            if (contentView.getLayout() == null) return;
            int line = contentView.getLayout().getLineForOffset(start);
            int y = Math.max(0, contentView.getLayout().getLineTop(line) - dp(48));
            contentScroll.smoothScrollTo(0, y);
        });
    }

    private void copyContent() {
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("PinJoin 日志", logText));
        Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show();
    }

    private String redactSecrets(String text) {
        Matcher matcher = SECRET_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, matcher.group(1) + "=***");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Button button(String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private LinearLayout.LayoutParams weightedParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable action;

        SimpleItemSelectedListener(Runnable action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                   int position, long id) {
            action.run();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
