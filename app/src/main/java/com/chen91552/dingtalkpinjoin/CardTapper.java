package com.chen91552.dingtalkpinjoin;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemClock;

/**
 * 卡片点击器 —— 在钉钉聊天页顶部查找 OneBox 置顶卡片容器并注入触摸事件。
 * <p>
 * 返回三态：
 * 0 = 容器不存在
 * 1 = 点击成功
 * 2 = 容器在但未布局好
 */
public class CardTapper {

    private static final String TAG = "CARD_TAP";

    /**
     * 在指定 Activity 的 DecorView 中查找 card_view_container 并注入触摸事件
     */
    public static int tap(Activity act) {
        if (act == null) {
            FileLogger.i(5, TAG, "tap: activity null");
            return 0;
        }

        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();

            // 通过资源 ID 查找 card_view_container
            int resId = act.getResources().getIdentifier(
                    "card_view_container", "id", act.getPackageName());
            View card = act.findViewById(resId);

            if (card == null) {
                FileLogger.i(5, TAG, "tap: card_view_container not found");
                return 0;
            }

            // 获取屏幕坐标
            int[] loc = new int[2];
            card.getLocationOnScreen(loc);
            int x = loc[0] + card.getWidth() / 2;
            int y = loc[1] + card.getHeight() / 2;

            if (x <= 0 || y <= 0) {
                FileLogger.i(5, TAG, "tap: card not laid out yet, bad coords (" + x + "," + y + ") -> retry");
                return 2;
            }

            FileLogger.i(5, TAG, "tap: card found at (" + x + "," + y + ") injecting touch");

            // 构造 DOWN + UP 事件对
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime,
                    MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 50,
                    MotionEvent.ACTION_UP, x, y, 0);

            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);

            down.recycle();
            up.recycle();

            FileLogger.i(5, TAG, "tap: touch injected successfully");
            return 1;

        } catch (Exception e) {
            FileLogger.i(5, TAG, "tap ERR " + e);
            return 0;
        }
    }
}