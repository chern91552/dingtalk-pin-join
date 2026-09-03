package com.chen91552.dingtalkpinjoin;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guards asynchronous host RPCs against callbacks that never arrive.
 */
final class RpcWatchdog {

    private static final long TIMEOUT_MS = 15000L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private RpcWatchdog() {}

    static Token arm(long taskId, String stage, Runnable timeoutAction) {
        Token token = new Token();
        MAIN_HANDLER.postDelayed(() -> {
            if (!token.claim() || !SilentJoin.isActive(taskId)) return;
            SilentJoin.log("[TIMEOUT] " + stage + " 超过 " + TIMEOUT_MS + "ms");
            timeoutAction.run();
        }, TIMEOUT_MS);
        return token;
    }

    static final class Token {
        private final AtomicBoolean completed = new AtomicBoolean();

        /**
         * Claims the one terminal outcome for this request.
         * A false result means timeout or another callback already won.
         */
        boolean claim() {
            return completed.compareAndSet(false, true);
        }
    }
}
