package com.l1.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无障碍点击引擎 v2：在锁定的单一坐标上按「间隔/次数/轮次」连点。
 *
 * 安全设计（安全第一）：
 * 1. 非阻塞派发：tapAt 只投递不等回执，引擎任何时刻都能在几毫秒内响应停止。
 * 2. 用户一摸屏幕立即停：监听 TYPE_TOUCH_INTERACTION_START（真实触屏事件）。
 *    自身派发手势若也被系统计入，用 grace 时间窗（戳长+120ms）豁免，不误停。
 * 3. 靶标变化即停：每 500ms 采样「前台包名 + 屏幕方向 + 落点控件特征」，
 *    与起始快照不一致立刻停（防切应用/旋转/弹窗导致连点误触新内容）。
 * 4. 息屏即停：注册 SCREEN_OFF 广播（OverlayService 侧另有接收器双保险）。
 * 5. 连续派发失败熔断：>=10 次自动停，防假死空转。
 */
public class TapService extends AccessibilityService {

    private static final String TAG = "TapService";
    private static volatile TapService sInstance = null;

    public static TapService instance() { return sInstance; }

    /** 连点参数：间隔 = 连点时间 / 次数，由 OverlayService 预先算好并校验。 */
    public static final class Params {
        public final long intervalMs;   // 点击间隔
        public final int tapsPerRound;  // 单轮点击次数（= 连点频次）
        public final long rounds;       // 轮数，>=1（0 已折算为 1）
        public final int x, y;          // 锁定点击坐标
        public Params(long iv, int taps, long rounds, int x, int y) {
            this.intervalMs = iv; this.tapsPerRound = taps;
            this.rounds = rounds; this.x = x; this.y = y;
        }
    }

    public interface Listener {
        /** 每完成一轮回调（done: 已完成轮数, total: -1 表示无限） */
        void onRoundDone(long done, long total);
        /** 结束回调：natural=自然跑完；reason 为中止原因（触摸/靶标变化/息屏等），用户主动停为 null */
        void onFinished(boolean natural, String reason);
    }

    static final String R_TOUCH  = "touch";     // 用户触摸屏幕
    static final String R_TARGET = "target";    // 靶标位置内容变化/应用切换
    static final String R_SCREEN = "screen";    // 息屏
    static final String R_FAILS  = "fails";     // 连续派发失败

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile Listener mListener;
    private volatile Thread mWorker;
    private volatile boolean mRunning;
    private volatile String mAbortReason;       // 异常中止原因（null = 用户主动停）

    private static final long STROKE_MS = 30;    // 单次按下-抬起时长
    private static final long MIN_GAP_MS = 50;   // 点击最小间隔（20Hz 上限）
    private static final long GRACE_MS = 30;     // 事件投递尾差：注入触摸从派发到送达窗口的延迟余量

    private final AtomicBoolean mDispatching = new AtomicBoolean(false);
    private volatile long mGraceUntil = 0;       // 此时刻前的触摸事件视为自身手势
    private volatile long mArmedAt = 0;          // 启动时刻：之后 400ms 内的触摸事件视为「开始」按钮的残余触摸
    private volatile int mFails = 0;

    /** 自身手势判别（静态镜像）：手势在途 或 投递尾差内。窗长 ≈ 戳长(30ms)+尾差(30ms)，
     *  远小于派发间隔下限 50ms——高频连点时每次派发之间必留空档，真实触摸不会被持续豁免。 */
    private static volatile long sGraceUntil = 0;
    static boolean inOwnGesture() {
        TapService s = sInstance;
        return (s != null && s.mDispatching.get()) || SystemClock.uptimeMillis() < sGraceUntil;
    }

    /** 息屏即停（引擎侧兜底）。 */
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (mRunning) stopTap(R_SCREEN);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        registerReceiver(mScreenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        Log.i(TAG, "connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // 用户一摸屏幕立即停；两类豁免：① 启动后 400ms 内 = 「开始」按钮的残余触摸
        //（系统对无障碍事件有 ~100ms 投递节流，开始按钮的触摸事件可能晚于引擎启动送达）
        // ② grace 窗内 = 自身派发手势
        // 注：ColorOS 不投递触摸类事件，主停止通道是悬浮窗 ACTION_OUTSIDE 旁听，此处为其他 ROM 的冗余保险
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START && mRunning) {
            long now = SystemClock.uptimeMillis();
            if (now - mArmedAt < 400 || now < mGraceUntil) return;
            Log.i(TAG, "touch-stop (a11y): user touch");
            stopTap(R_TOUCH);
        }
    }

    @Override public void onInterrupt() {
        // 关键：onInterrupt 只是"事件反馈被系统临时打断"（共存的无障碍服务如 GKD/
        // Select to Speak 在手势派发时就会触发它），手势派发能力仍在，绝不能停引擎。
        // 真正的服务关闭走 onDestroy；派发真失败有 10 次熔断兜底。安全侧依然成立。
        Log.w(TAG, "onInterrupt (coexisting a11y service triggered; engine keeps running)");
    }

    @Override public void onDestroy() {
        Log.i(TAG, "onDestroy (service really shut down)");
        try { unregisterReceiver(mScreenReceiver); } catch (Exception ig) {}
        stopTap(null);
        sInstance = null;
        super.onDestroy();
    }

    public boolean isRunning() { return mRunning; }

    public void setListener(Listener l) { mListener = l; }

    public synchronized boolean startTap(Params p) {
        if (mRunning) stopTap(null);
        if (sInstance == null) return false;
        if (p.intervalMs < MIN_GAP_MS || p.tapsPerRound < 1 || p.rounds < 1) return false;
        mRunning = true;
        mAbortReason = null;
        mFails = 0;
        mArmedAt = SystemClock.uptimeMillis();
        mGraceUntil = mArmedAt + 400;   // 启动缓冲期内触摸事件一律豁免
        mWorker = new Thread(new Runner(p), "tap-worker");
        mWorker.start();
        Log.i(TAG, "startTap: iv=" + p.intervalMs + "ms taps=" + p.tapsPerRound
                + " rounds=" + p.rounds + " pt=" + p.x + "," + p.y);
        return true;
    }

    /** 用户主动停止，幂等。 */
    public synchronized void stopTap() { stopTap(null); }

    /** 立即停止；reason 非空记为异常中止（触发 onFinished(false, reason)）。 */
    private void stopTap(String reason) {
        if (reason != null) mAbortReason = reason;
        mRunning = false;
        Thread w = mWorker;
        mWorker = null;
        if (w != null) w.interrupt();
    }

    /** 引擎循环：按间隔点击 tapsPerRound 次 = 一轮；跑满 rounds 轮自然结束。 */
    private final class Runner implements Runnable {
        final Params p;
        Runner(Params pp) { p = pp; }
        @Override public void run() {
            long roundDone = 0;
            boolean natural = false;
            String reason = null;
            String basePkg = null, baseSig = null;      // 起始快照（首次成功采样时建立）
            int baseOri = -1;
            long lastProbe = 0;
            try {
                outer:
                while (mRunning) {
                    for (int i = 0; i < p.tapsPerRound && mRunning; i++) {
                        if (mRunning) tapAt(p.x, p.y);
                        long next = SystemClock.uptimeMillis() + p.intervalMs;
                        while (mRunning) {
                            long now = SystemClock.uptimeMillis();
                            if (now >= next) break;
                            if (mFails >= 10) { reason = R_FAILS; break outer; }
                            // 每 500ms 靶标变化采样
                            if (now - lastProbe >= 500) {
                                lastProbe = now;
                                int ori = getResources().getConfiguration().orientation;
                                String pkg = rootPkg(), sig = pointSig(p.x, p.y);
                                if (pkg != null || sig != null) {
                                    if (basePkg == null && baseSig == null) {
                                        basePkg = pkg; baseSig = sig; baseOri = ori;
                                        Log.i(TAG, "baseline: pkg=" + pkg + " ori=" + ori + " sig=" + sig);
                                    } else if (basePkg == null && pkg != null) {
                                        // 原锚是无包名窗口（多为共存无障碍服务的悬浮层），拿到真实前台包名后改锚
                                        basePkg = pkg; baseSig = sig; baseOri = ori;
                                        Log.i(TAG, "re-baseline: pkg=" + pkg + " sig=" + sig);
                                    } else if (changed(basePkg, pkg, baseSig, sig, baseOri, ori)) {
                                        Log.i(TAG, "target-changed: pkg " + basePkg + "->" + pkg
                                                + " ori " + baseOri + "->" + ori + " sig " + baseSig + "->" + sig);
                                        reason = R_TARGET;
                                        break outer;
                                    }
                                }
                            }
                            sleep(Math.min(50L, next - now));
                        }
                    }
                    if (!mRunning) break;
                    roundDone++;
                    if (mListener != null) {
                        final long d = roundDone;
                        mMain.post(() -> { if (mListener != null)
                            mListener.onRoundDone(d, p.rounds); });
                    }
                    if (roundDone >= p.rounds) { natural = true; break; }
                    sleep(200); // 轮间小歇
                }
            } catch (InterruptedException ignored) {
            } finally {
                mRunning = false;
                Log.i(TAG, "engine-end: natural=" + natural + " reason=" + (reason != null ? reason : mAbortReason)
                        + " roundDone=" + roundDone);
                if (mWorker == Thread.currentThread()) {
                    mWorker = null;
                    final boolean nat = natural;
                    final String rs = reason != null ? reason : mAbortReason;
                    mAbortReason = null;
                    final Listener l = mListener;
                    mListener = null;
                    if (l != null) mMain.post(() -> l.onFinished(nat, rs));
                }
            }
        }
    }

    private static boolean changed(String bp, String p, String bs, String s, int bo, int o) {
        if (bo != -1 && o != bo) return true;                      // 屏幕旋转
        if (bp != null && p != null && !bp.equals(p)) return true; // 前台应用切换
        if (bs != null && s != null && !bs.equals(s)) return true; // 落点控件特征变化
        return false;
    }

    private String rootPkg() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            return r == null ? null : String.valueOf(r.getPackageName());
        } catch (Exception e) { return null; }
    }

    /** 落点所在控件的稳定特征（类名+ID+文本+ bounds），取不到返回 null（不参与比对）。 */
    private String pointSig(int x, int y) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            AccessibilityNodeInfo hit = findNodeAt(root, x, y, 0);
            if (hit == null) return null;
            Rect b = new Rect();
            hit.getBoundsInScreen(b);
            return hit.getClassName() + "|" + hit.getViewIdResourceName() + "|"
                    + hit.getText() + "|" + b.left + "," + b.top + "," + b.width() + "," + b.height();
        } catch (Exception e) { return null; }
    }

    private AccessibilityNodeInfo findNodeAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        if (n == null || depth > 30) return null;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (!b.contains(x, y)) return null;
        int cnt = n.getChildCount();
        for (int i = 0; i < cnt; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo r = findNodeAt(c, x, y, depth + 1);
            if (r != null) return r;
        }
        return n;
    }

    /** 非阻塞派发一次点击。在途（上一次手势未结束）则本轮跳过，绝不堆积。 */
    private void tapAt(final int x, final int y) {
        if (!mRunning || !mDispatching.compareAndSet(false, true)) return;
        mGraceUntil = SystemClock.uptimeMillis() + STROKE_MS + GRACE_MS;
        sGraceUntil = mGraceUntil;
        mMain.post(() -> {
            boolean accepted = false;
            if (mRunning) {
                try {
                    Path path = new Path();
                    path.moveTo(x, y);
                    path.lineTo(x + 0.1f, y + 0.1f);
                    GestureDescription g = new GestureDescription.Builder()
                            .addStroke(new GestureDescription.StrokeDescription(path, 0, STROKE_MS))
                            .build();
                    accepted = dispatchGesture(g, new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription gd) {
                            mFails = 0; mDispatching.set(false);
                        }
                        @Override public void onCancelled(GestureDescription gd) {
                            mFails++;
                            Log.w(TAG, "gesture cancelled, fails=" + mFails);
                            mDispatching.set(false);
                        }
                    }, null);
                } catch (Exception e) {
                    Log.w(TAG, "dispatchGesture threw", e);
                    accepted = false;
                }
            }
            if (!accepted) { mFails++; mDispatching.set(false); }
        });
    }

    private static void sleep(long ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }
}
