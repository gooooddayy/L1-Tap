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
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无障碍点击引擎 v2：在锁定的单一坐标上按「间隔/次数/轮次」连点。
 *
 * 安全设计（安全第一）：
 * 1. 非阻塞派发：tapAt 只投递不等回执，引擎任何时刻都能在几毫秒内响应停止。
 * 2. 用户一摸屏幕立即停（均与连点频率无关）：
 *    ① 悬浮窗海绵层（OverlayService）：执行期全屏覆盖、仅锁点 ±2dp 小孔，孔外摸到即停（主通道）；
 *    ② 注入手势被系统取消 = 真实触摸抢占 → 立即停（侧滑/系统抢手势的精确信号）；
 *    ③ TYPE_TOUCH_INTERACTION_START（其他 ROM）+ 短豁免窗冗余层。
 *    注：v1.0.0ai 定案——ColorOS 触摸副本的坐标/旗标/工具类型均不可复原，真手与注入同签名，
 *    「旁听副本 + 判别」整条路线作废（aa~ah 五轮实测证据见 docs/全版本审查-*.md）。
 * 3. 切换应用即停：监听 TYPE_WINDOW_STATE_CHANGED；切应用比包名，同应用内切页比
 *    Activity 类名（an：真页面事件类名含「Activity」，弹层/对话框不含 → 不误杀）。
 *    同应用内其余内容变化由第 4 条采样层兜底停止。
 * 4. 靶标变化即停：每 250ms 采样「前台包名 + 屏幕方向 + 落点控件特征（含边界）」，
 *    与起始快照不一致立刻停（落点控件消失同样判为变化，堵住旧版空值跳过的漏检）。
 * 5. 息屏即停：注册 SCREEN_OFF 广播（OverlayService 侧另有接收器双保险）。
 * 6. 连续派发失败熔断：>=10 次自动停，防假死空转。
 * 7. 派发回调看门狗：个别 ROM 手势被吞且不回调时强制解锁派发标志，避免「引擎在跑但一次不点」。
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
    static final String R_TARGET = "target";    // 靶标位置内容变化/页面切换/应用切换
    static final String R_SCREEN = "screen";    // 息屏
    static final String R_FAILS  = "fails";     // 连续派发失败

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile Listener mListener;
    private volatile Thread mWorker;
    private volatile boolean mRunning;
    private volatile String mAbortReason;       // 异常中止原因（null = 用户主动停）

    private static final long STROKE_MS = 15;    // 单击按下-抬起时长：越短，侧滑开始瞬间"在途被误点"的概率越低（概率=戳长/间隔）
    private static final long MIN_GAP_MS = 50;   // 点击最小间隔（20Hz 上限）
    private static final long GRACE_MS = 120;    // 事件投递尾差：注入触摸从派发到送达窗口/无障碍通道的总延迟余量
                                                 // （无障碍事件有 ~100ms 投递节流，戳长 15 + 尾差 120 = 135ms 总豁免窗）
    private static final long WATCHDOG_MS = 200; // 派发回调丢失兜底：派发后超过此时长未见回调则强制解锁
    private static final long FIRST_DELAY_MS = 120; // 首击延迟（一次性）：靶标窗口 FLAG_NOT_TOUCHABLE 的 WM
                                                 // 生效有数帧延迟，立即派发首击可能被自家靶窗吞掉，
                                                 // 表现为每次开跑少点 1 次（v1.0.0ag/ah 实测 6/8 会话出现）

    private final AtomicBoolean mDispatching = new AtomicBoolean(false);
    private volatile long mGraceUntil = 0;       // 此时刻前的触摸事件视为自身手势
    private volatile long mDispatchAt = 0;       // 最近一次派发时刻（看门狗基准）
    private volatile long mArmedAt = 0;          // 启动时刻：之后 400ms 内的触摸事件视为「开始」按钮的残余触摸
    private volatile int mTapX = 0, mTapY = 0;   // 当前锁定落点（供海绵层判别拖动区）
    private volatile int mFails = 0;

    // 页面身份（由 TYPE_WINDOW_STATE_CHANGED 滚动更新，滤掉自身、无包名与输入法窗口）：
    // 按返回/跳转换了页面 → 立即停止，不再依赖落点控件特征（视频/图片类页面取不到节点，是旧漏洞）
    private volatile String mPagePkg = null, mPageCls = null;
    private volatile String mBasePagePkg = null, mBasePageCls = null;   // 引擎启动时的页面锚
    private volatile Set<String> mImePkgs = null;   // 已启用输入法包名（懒加载）：键盘事件不参与页面判定
    private volatile long mDispatchHoldUntil = 0;   // ao：systemui 窗口事件后的派发暂停截止时刻

    /** 自身手势判别（静态镜像）：手势在途 或 投递尾差内（戳长 15ms + 尾差 120ms）。
     *  窗长覆盖无障碍事件投递节流（~100ms），确保注入点击不会被误判成用户触摸。 */
    private static volatile long sGraceUntil = 0;
    static boolean inOwnGesture() {
        TapService s = sInstance;
        return (s != null && s.mDispatching.get()) || SystemClock.uptimeMillis() < sGraceUntil;
    }

    /** 触摸坐标是否落在锁定落点 ±radiusDp 内。v1.0.0ai 起供海绵层使用：
     *  锁点 80dp 内的海绵触摸 = 拖动模式（停+换位），其余位置 DOWN 即停。 */
    static boolean nearTapPoint(float x, float y, float radiusDp) {
        TapService s = sInstance;
        if (s == null) return false;
        float r = radiusDp * s.getResources().getDisplayMetrics().density;
        float dx = x - s.mTapX, dy = y - s.mTapY;
        return dx * dx + dy * dy <= r * r;
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
        // 注：不使用按键过滤（FLAG_REQUEST_FILTER_KEY_EVENTS）。该能力在部分 ROM 上会干扰
        // 手势派发链路，且返回/主页的停止已由「页面切换即停 + 触摸旁听」两条通道覆盖。
        Log.i(TAG, "connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // 页面切换即停（返回/跳转/弹窗）：以「前台包名 + 窗口类名」为页面身份。
        // 这是按键类（返回键）之外的主通道——按一下返回换了页面，这里即时停止。
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pn = event.getPackageName();
            String p = pn == null ? null : pn.toString();
            // 无包名窗口/自身面板/系统界面（通知栏、音量条、分享面板等）：不参与页面判定
            if (p == null || p.equals(getPackageName())) return;
            // systemui 窗口事件（返回手势提示层/通知横幅/音量条等）：不参与页面判定，
            // 但 ao 起「暂停派发 400ms」——返回侧滑一开始就有 systemui 提示层事件，
            // 暂停窗让手势期间不再有新击落下，换页由 page-changed/采样在窗内裁决。
            // 不停机：横幅消失后事件流停止，暂停自动解除，点击节奏最多错过 1~2 击。
            if (p.equals("com.android.systemui")) {
                if (mRunning) {
                    CharSequence sc = event.getClassName();
                    mDispatchHoldUntil = SystemClock.uptimeMillis() + 400;
                    Log.i(TAG, "sysui-window: hold dispatch 400ms ("
                            + (sc == null ? "?" : sc.toString()) + ")");
                }
                return;
            }
            // 输入法窗口（键盘弹出/收起、候选栏）不是页面变化：既不能污染页面锚，
            // 也不能在启动瞬间被误判为「切页」而停止——这是「点开始后不点击」的元凶之一。
            if (isImePkg(p)) return;
            CharSequence cn = event.getClassName();
            String c = cn == null ? null : cn.toString();
            // 页面锚只认 Activity 类窗口（an 起同应用内切页由本层主判）：真换页的事件类名
            // 几乎都含「Activity」；弹层/对话框是 FrameLayout/Dialog/PopupWindow 之类，不含
            // → 弹层开/关既不更新锚也不触发停止（v1.0.0ab「弹层误杀」教训不回归）。
            boolean isAct = c != null && c.contains("Activity");
            if (isAct) { mPagePkg = p; mPageCls = c; }
            if (mRunning) {
                if (mBasePagePkg == null) {
                    if (isAct) {
                        mBasePagePkg = p; mBasePageCls = c;   // 启动时未拿到锚（罕见），以首个 Activity 事件改锚
                        Log.i(TAG, "page-baseline: " + p + "/" + c);
                    }
                } else if (!p.equals(mBasePagePkg)) {
                    Log.i(TAG, "page-changed(pkg): " + mBasePagePkg + "/" + mBasePageCls + " -> " + p + "/" + c);
                    stopTap(R_TARGET);
                } else if (isAct && mBasePageCls != null && !c.equals(mBasePageCls)) {
                    if (mBasePageCls.contains("Activity")) {
                        // 同应用内 Activity 切换（返回/跳转）：立即停
                        Log.i(TAG, "page-changed(act): " + mBasePagePkg + "/" + mBasePageCls + " -> " + p + "/" + c);
                        stopTap(R_TARGET);
                    } else {
                        // 起始锚不是 Activity（启动瞬间只收到过弹层事件等罕见时序）：改锚不停
                        mBasePageCls = c;
                        Log.i(TAG, "page-rebaseline: " + p + "/" + c);
                    }
                }
            }
            return;
        }
        // 用户一摸屏幕立即停；两类豁免：① 启动后 400ms 内 = 「开始」按钮的残余触摸
        //（系统对无障碍事件有 ~100ms 投递节流，开始按钮的触摸事件可能晚于引擎启动送达）
        // ② grace 窗内 = 自身派发手势
        // 注：ColorOS 不投递触摸类事件，主停止通道是悬浮窗海绵层（摸到即停），此处为其他 ROM 的冗余保险
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START && mRunning) {
            long now = SystemClock.uptimeMillis();
            if (now - mArmedAt < 400 || now < mGraceUntil) return;
            Log.i(TAG, "touch-stop (a11y): user touch");
            stopTap(R_TOUCH);
        }
    }

    /** ao：派发前核对当前活动窗口（每击一次）。换页瞬间「先停再派」，收窄换页盲区——
     *  a11y 事件到达前 ~100-200ms 里新页窗口已可见可收触摸，an 实测误触即发生于此。
     *  root 与窗口事件同源但少一层事件投递延迟；取不到 root 时放行（不阻塞点击）。
     *  判定与页面事件层一致：pkg 变了算换页；活动窗口是 Activity 类且与锚不同算换页；
     *  弹层成为活动窗口（FrameLayout 等）不算。 */
    private boolean pageDrifted() {
        if (mBasePagePkg == null) return false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence rp = root.getPackageName(), rc = root.getClassName();
            String pkg = rp == null ? null : rp.toString();
            String cls = rc == null ? null : rc.toString();
            root.recycle();
            boolean drift = (pkg != null && !pkg.equals(mBasePagePkg))
                    || (cls != null && cls.contains("Activity")
                        && mBasePageCls != null && !cls.equals(mBasePageCls));
            if (drift) Log.i(TAG, "page-drift(root): " + mBasePagePkg + "/" + mBasePageCls
                    + " -> " + pkg + "/" + cls);
            return drift;
        } catch (Exception ig) { return false; }
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
        mDispatching.set(false);   // 会话级复位：上轮若回调丢失，绝不把卡死带进新一轮
        mDispatchAt = 0;
        mArmedAt = SystemClock.uptimeMillis();
        mGraceUntil = mArmedAt + 400;   // 启动缓冲期内触摸事件一律豁免
        mBasePagePkg = mPagePkg; mBasePageCls = mPageCls;   // 页面锚：启动时所在页面
        mTapX = p.x; mTapY = p.y;                           // 落点：供 ACTION_OUTSIDE 坐标判别
        mWorker = new Thread(new Runner(p), "tap-worker");
        mWorker.start();
        Log.i(TAG, "startTap: iv=" + p.intervalMs + "ms taps=" + p.tapsPerRound
                + " rounds=" + p.rounds + " pt=" + p.x + "," + p.y
                + " page=" + mBasePagePkg + "/" + mBasePageCls);
        return true;
    }

    /** 用户主动停止，幂等。 */
    public synchronized void stopTap() { stopTap(null); }

    /** 立即停止；reason 非空记为异常中止（触发 onFinished(false, reason)）。
     *  不清 mWorker：worker 的 finally 靠「mWorker == 自己」决定是否投递 onFinished——
     *  若在这里清空，外部线程（页面层/息屏广播）发起的停止会让该判定失败、回调丢失，
     *  UI 永远卡在执行态（v1.0.0ab 实测教训）。新一轮 startTap 覆盖 mWorker 后，
     *  旧 worker 的 finally 因身份不符自动跳过，不会串场。 */
    private void stopTap(String reason) {
        if (reason != null) mAbortReason = reason;
        mRunning = false;
        Thread w = mWorker;
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
                sleep(FIRST_DELAY_MS);   // 首击延迟：等 WM 落实窗口穿透标志（见常量注释；一次性，不影响节奏）
                outer:
                while (mRunning) {
                    for (int i = 0; i < p.tapsPerRound && mRunning; i++) {
                        if (mRunning) {
                            // ao：换页盲区收窄——派发前三道闸。
                            // ① systemui 暂停窗：返回手势一开始 ColorOS 就弹 systemui 提示层，
                            //    暂停 400ms → 手势期间不再有新击落下，换页在暂停窗内被裁决；
                            if (SystemClock.uptimeMillis() < mDispatchHoldUntil) {
                                Log.i(TAG, "dispatch-held: skip tap during sysui window");
                            }
                            // ② 派发前核对活动窗口：换页瞬间先停再派（比 a11y 事件投递快一层）
                            else if (pageDrifted()) {
                                reason = R_TARGET;
                                break outer;
                            } else {
                                tapAt(p.x, p.y);
                            }
                        }
                        long next = SystemClock.uptimeMillis() + p.intervalMs;
                        while (mRunning) {
                            long now = SystemClock.uptimeMillis();
                            if (now >= next) break;
                            if (mFails >= 10) { reason = R_FAILS; break outer; }
                            // 每 250ms 靶标变化采样（含边界：内容/控件移位即判变化）
                            if (now - lastProbe >= 250) {
                                lastProbe = now;
                                int ori = getResources().getConfiguration().orientation;
                                String pkg = rootPkg(), sig = pointSig(p.x, p.y);
                                if (basePkg == null && baseSig == null) {
                                    // 首次锚定：拿到包名或落点特征任一即可
                                    if (pkg != null || sig != null) {
                                        basePkg = pkg; baseSig = sig; baseOri = ori;
                                        Log.i(TAG, "baseline: pkg=" + pkg + " ori=" + ori + " sig=" + sig);
                                    }
                                } else if (basePkg == null && pkg != null) {
                                    // 原锚是无包名窗口（多为共存无障碍服务的悬浮层），拿到真实前台包名后改锚
                                    basePkg = pkg; baseSig = sig; baseOri = ori;
                                    Log.i(TAG, "re-baseline: pkg=" + pkg + " sig=" + sig);
                                } else {
                                    // 比对（空值跳过已修复：落点控件消失也判为变化——
                                    // 返回换页后常取不到落点节点，两边非空才比对会漏检）。
                                    // an 恢复「单次差异即停」（al 原样）：换页/侧滑返回的
                                    // 兜底秒停依赖它——am 的「连续 3 次完全相同」稳定确认在
                                    // 闪烁页面上永远凑不齐（bounds 每次抖几 px + TextView↔null
                                    // 交替）→ 采样瘫痪 → 侧滑返回 1~4.3s 不停（an 复盘定案）。
                                    // 单次差异的误停副作用由海绵延迟拆除根治（OverlayService
                                    // scheduleSpongeRemoval）：停了也不误点，采样不再背防误点的锅。
                                    String why = null;
                                    if (baseOri != -1 && ori != baseOri) why = "ori";
                                    else if (basePkg != null && pkg != null && !basePkg.equals(pkg)) why = "pkg";
                                    else if (baseSig != null && sig != null && !baseSig.equals(sig)) why = "sig";
                                    else if (baseSig != null && sig == null && pkg != null) why = "sig-gone";
                                    if (why != null) {
                                        // ap：自更新判定——sig 差异但「类名+ID」未变，是锁点控件
                                        // 对自己点击的生效反馈（点赞数 165赞→166赞 原地刷新文本，
                                        // 边界抖几 px），不是靶标变化 → 改锚续点。pointSig 只返回
                                        // 包含锁点的最深节点，sig 非空即「仍压着锁点」，几何条件
                                        // 天然满足；类名/ID 变了 = 手指下换了别的东西（弹层/换页）
                                        // → 照旧停止。
                                        if ("sig".equals(why) && sameKind(baseSig, sig)) {
                                            baseSig = sig;
                                            Log.i(TAG, "target-selfupdate: re-baseline " + sig);
                                        } else {
                                            Log.i(TAG, "target-changed(" + why + "): pkg " + basePkg + "->" + pkg
                                                    + " ori " + baseOri + "->" + ori + " sig " + baseSig + "->" + sig);
                                            reason = R_TARGET;
                                            break outer;
                                        }
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

    private String rootPkg() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) return null;
            CharSequence pn = r.getPackageName();
            return pn == null ? null : pn.toString();
        } catch (Exception e) { return null; }
    }

    /** 是否输入法包（懒加载缓存「已启用输入法」列表；查询失败则永不判为输入法）。 */
    private boolean isImePkg(String p) {
        Set<String> s = mImePkgs;
        if (s == null) {
            s = new HashSet<>();
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                        s.add(imi.getPackageName());
                    }
                }
            } catch (Exception ig) {}
            mImePkgs = s;
        }
        return s.contains(p);
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

    /** 两次 sig 的「类名|ID」是否一致：点赞/计数类自更新只有文本与边界微变，
     *  类名或 ID 变了 = 锁点下换了别的东西（弹层/换页），不算自更新。 */
    private static boolean sameKind(String a, String b) {
        return kindOf(a).equals(kindOf(b));
    }

    private static String kindOf(String sig) {
        String[] f = sig.split("\\|", 3);
        return f.length >= 2 ? f[0] + "|" + f[1] : sig;
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
        if (!mRunning) return;
        long now = SystemClock.uptimeMillis();
        // 看门狗：个别 ROM 在手势被取消/进程冻结时不回调 onCompleted/onCancelled，
        // 不兜底会让 mDispatching 永久卡 true，表现为「引擎在跑但一次都不点」。
        if (mDispatching.get() && now - mDispatchAt > WATCHDOG_MS) {
            Log.w(TAG, "dispatch watchdog: force reset stuck flag");
            mDispatching.set(false);
        }
        if (!mDispatching.compareAndSet(false, true)) return;
        mDispatchAt = now;
        mGraceUntil = now + STROKE_MS + GRACE_MS;
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
                            // 真实触摸会抢占（取消）在途的注入手势：取消 ≈ 用户正在触碰屏幕。
                            // 这是与连点频率无关的精确「触摸即停」信号（v1.0.0ac 实测：
                            // 用户每次触摸/侧滑，日志必现 gesture cancelled，但旧代码只计数不停）。
                            if (mRunning) {
                                Log.i(TAG, "gesture cancelled by user touch -> stop");
                                stopTap(R_TOUCH);
                            }
                            mDispatching.set(false);
                        }
                    }, null);
                    Log.i(TAG, "tap-dispatch: " + x + "," + y + " accepted=" + accepted);
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
