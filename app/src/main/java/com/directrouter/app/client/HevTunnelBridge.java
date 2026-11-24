package com.directrouter.app.client;

import android.util.Log;
import com.wgtunnel.hevtunnel.TProxyService;

/**
 * Thin Java bridge over com.wgtunnel.hevtunnel.TProxyService to isolate
 * native JNI invocations and Kotlin metadata differences cleanly.
 */
public final class HevTunnelBridge {
    private static final String TAG = "HevTunnelBridge";

    private HevTunnelBridge() {
    }

    public static boolean start(String configPath, int tunFd) {
        if (configPath == null || configPath.isEmpty() || tunFd < 0) {
            return false;
        }
        try {
            return TProxyService.TProxyStartService(configPath, tunFd);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            Log.e(TAG, "Failed to start hev-socks5-tunnel", e);
            return false;
        }
    }

    public static boolean stop() {
        try {
            return TProxyService.TProxyStopService();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            Log.e(TAG, "Failed to stop hev-socks5-tunnel", e);
            return false;
        }
    }

    public static boolean isRunning() {
        try {
            return TProxyService.TProxyIsRunning();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            Log.w(TAG, "Failed to check hev-socks5-tunnel status", e);
            return false;
        }
    }

    /**
     * Returns [txPackets, txBytes, rxPackets, rxBytes], or a zeroed array when unavailable.
     */
    public static long[] getStats() {
        try {
            long[] stats = TProxyService.TProxyGetStats();
            if (stats != null && stats.length >= 4) {
                return stats;
            }
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            Log.w(TAG, "Failed to read hev-socks5-tunnel stats", e);
        }
        return new long[]{0L, 0L, 0L, 0L};
    }
}
