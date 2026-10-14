package com.l1.autoclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

/**
 * 悬浮控件前台服务 v2：设置/停止钮 + 靶标两个悬浮窗，设置面板与执行状态机。
 *
 * 安全设计（安全第一）：
 * A. 开始时锁点一次、快照进 Params：执行期间引擎不回调 UI（消除双向耦合）。
 * B. 执行中两个悬浮窗的触摸策略：
 *    - 靶标整体 FLAG_NOT_TOUCHABLE：只显示不拦截，触摸穿透到下面的应用；
 *    - 红色停止钮一碰即停（ACTION_DOWN 立即停）。
 * C. 用户一有操作就停：执行期挂「海绵监听层」（4 块矩形铺满全屏，仅锁点 ±2dp 小孔）。
 *    自动点击只能从孔里穿过 → 摸到海绵 = 100% 用户触摸，摸到即停，无需任何真手/注入判别。
 *    （v1.0.0ai 架构定案：ColorOS 的触摸副本坐标/旗标/工具类型全部不可复原，
 *    「旁听副本 + 判别真手」整条路线作废——aa~ah 五轮均折在此。）
 * D. 靶标变化即停：引擎侧每 250ms 比对落点特征/前台应用/屏幕方向。
 * E. 防锁屏：执行中悬浮窗带 FLAG_KEEP_SCREEN_ON（屏幕常亮）；息屏广播立即停。
 * F. 停止通道：海绵层触摸 / 红钮 / 注入手势被取消（侧滑抢占）/ 通知栏 / 息屏 / 切走页面 / 服务被杀。
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "com.l1.autoclicker.START";
    public static final String ACTION_STOP = "com.l1.autoclicker.STOP";

    private static final int STATE_IDLE = 0;
    private static final int STATE_RUNNING = 1;

    private static final String PREFS = "overlay";
    private static final String K_SX = "sx", K_SY = "sy", K_TX = "tx", K_TY = "ty";
    private static final String K_DUR = "dur", K_CNT = "cnt", K_ROUNDS = "rounds";
    private static final int NOTIF_ID = 1;

    // 海绵监听层（执行期铺满全屏）：仅锁点周围留 ±2dp 小孔，孔外任意一点触摸 = 用户。
    // DOWN 落在锁点 80dp 内 → 拖动模式（超 slop 即停+换位；轻点即停）；其余位置 DOWN 即停。
    private static final int SPONGE_DRAG_DP = 80;
    private static final int SPONGE_HOLE_DP = 2;

    /** 供 MainActivity 在 onPause 时请求停止连点（服务未必存在，须判空）。 */
    public static volatile OverlayService instance;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private WindowManager mWm;
    private SharedPreferences mPrefs;

    private SettingsControl mSettingsBtn;
    private TargetControl mTarget;
    private WindowManager.LayoutParams mSettingsLp, mTargetLp;
    private boolean mOverlaysAdded;

    private int mState = STATE_IDLE;
    private int mBtnSize, mTgtSize;

    // 屏幕与安全区
    private int mScreenW, mScreenH, mSafeL, mSafeT, mSafeR, mSafeB;

    private View mPanelRoot;
    private ObjectAnimator mBlink;

    // 海绵监听层：4 块矩形窗铺满全屏（仅锁点 ±2dp 小孔），只在执行期存在
    private final View[] mSpongeViews = new View[4];
    private boolean mSpongeAdded;
    private Runnable mSpongePending;   // 延迟挂海绵的任务（startClicking 错开首击用）
    private Runnable mSpongeRemovePending;   // 停止后延迟拆海绵的任务（an：防自动停后触摸直达应用误点）
    private boolean mSpongeTouching;   // 海绵上有手指在屏：停止时延迟拆除，拖动不被打断

    /** 息屏即停（UI 侧；引擎侧 TapService 另有接收器双保险）。 */
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { stopClicking(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        computeSafeArea();
        registerReceiver(mScreenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        startForegroundCompat("待命");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopClicking();
        } else {
            ensureOverlays();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        stopClicking();
        mSpongeTouching = false;   // 服务销毁：强制拆海绵
        if (mSpongePending != null) mMain.removeCallbacks(mSpongePending);
        if (mSpongeRemovePending != null) mMain.removeCallbacks(mSpongeRemovePending);
        removeSponge();
        try { unregisterReceiver(mScreenReceiver); } catch (Exception ig) {}
        removeOverlays();
        dismissSettingsPanel();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ============ 悬浮控件 ============

    private void ensureOverlays() {
        if (mOverlaysAdded) { return; }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            stopSelf();
            return;
        }
        // 设置钮 48dp（与桌面图标同级）；靶标 24dp（特别小）
        mBtnSize = (int) dp(48);
        mTgtSize = (int) dp(24);
        mSettingsBtn = new SettingsControl(this);
        mTarget = new TargetControl(this);
        int sb = mBtnSize, tb = mTgtSize;

        mSettingsLp = noFit(new WindowManager.LayoutParams(sb, sb,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        // 关键：以整屏(含状态栏)为坐标系，与手势派发的绝对坐标一致，否则锁点整体偏移
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT));
        mSettingsLp.gravity = Gravity.TOP | Gravity.START;
        mTargetLp = noFit(new WindowManager.LayoutParams(tb, tb, mSettingsLp.type,
                mSettingsLp.flags, PixelFormat.TRANSLUCENT));
        mTargetLp.gravity = Gravity.TOP | Gravity.START;

        // 初始位置：默认设置钮在右上角、靶标在内容区中央
        int y0 = mSafeT + (int) dp(8);
        mSettingsLp.x = mSafeR - sb - (int) dp(8);
        mSettingsLp.y = y0;
        mTargetLp.x = Math.max(mSafeL, (mScreenW - tb) / 2);
        mTargetLp.y = Math.max(mSafeT, (mSafeT + mSafeB - tb) / 2);
        restorePos();
        clampLp(mSettingsLp, sb, sb);
        clampLp(mTargetLp, tb, tb);

        mSettingsBtn.setOnTouchListener(settingsTouch);
        mTarget.setOnTouchListener(targetTouch);

        try {
            mWm.addView(mSettingsBtn, mSettingsLp);
            mWm.addView(mTarget, mTargetLp);
            mOverlaysAdded = true;
        } catch (Exception e) {
            toast("悬浮窗添加失败：" + e.getMessage());
            stopSelf();
        }
    }

    private void removeOverlays() {
        if (!mOverlaysAdded) return;
        try { mWm.removeView(mSettingsBtn); } catch (Exception ig) {}
        try { mWm.removeView(mTarget); } catch (Exception ig) {}
        mOverlaysAdded = false;
    }

    private void clampLp(WindowManager.LayoutParams lp, int w, int h) {
        lp.x = Math.max(0, Math.min(lp.x, mScreenW - w));
        lp.y = Math.max(0, Math.min(lp.y, mScreenH - h));
    }

    private void restorePos() {
        mSettingsLp.x = mPrefs.getInt(K_SX, mSettingsLp.x);
        mSettingsLp.y = mPrefs.getInt(K_SY, mSettingsLp.y);
        mTargetLp.x = mPrefs.getInt(K_TX, mTargetLp.x);
        mTargetLp.y = mPrefs.getInt(K_TY, mTargetLp.y);
    }

    private void savePos() {
        mPrefs.edit().putInt(K_SX, mSettingsLp.x).putInt(K_SY, mSettingsLp.y)
                .putInt(K_TX, mTargetLp.x).putInt(K_TY, mTargetLp.y).apply();
    }

    // ============ 触摸：设置/停止钮 ============

    private float mDownX, mDownY;
    private int mDragStartX, mDragStartY;
    private boolean mDragging;
    private boolean mDragCapable;   // 海绵 DOWN 落在锁点 80dp 内：允许拖动换位
    private final int mSlop = ViewConfiguration.getTouchSlop();

    private final View.OnTouchListener settingsTouch = (v, ev) -> {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mState == STATE_RUNNING) {
                    // 执行中红钮一碰即停，无条件。自身连点只落在靶标点（锁点时已避开设置钮），
                    // 永远不会落进本窗口，无需豁免——高频连点下豁免窗会拦掉真实手指，绝不能按它判别。
                    android.util.Log.i("AutoClicker", "btn-down: running -> STOP");
                    stopClicking("已停止");
                    return true;
                }
                mDownX = ev.getRawX(); mDownY = ev.getRawY();
                mDragStartX = mSettingsLp.x; mDragStartY = mSettingsLp.y;
                mDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mState == STATE_RUNNING) return true;
                float dx = ev.getRawX() - mDownX, dy = ev.getRawY() - mDownY;
                if (!mDragging && (Math.abs(dx) > mSlop || Math.abs(dy) > mSlop)) mDragging = true;
                if (mDragging) {
                    mSettingsLp.x = mDragStartX + (int) dx;
                    mSettingsLp.y = mDragStartY + (int) dy;
                    clampLp(mSettingsLp, mBtnSize, mBtnSize);
                    mWm.updateViewLayout(v, mSettingsLp);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!mDragging && mState == STATE_IDLE) showSettingsPanel();
                else if (mDragging) savePos();
                mDragging = false;
                return true;
        }
        return true;
    };

    // ============ 触摸：靶标（仅待命时可拖） ============

    private final View.OnTouchListener targetTouch = (v, ev) -> {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mState == STATE_RUNNING) {
                    // 双保险（正常应已被 FLAG_NOT_TOUCHABLE 穿透，收不到事件）。
                    // 收得到 = 穿透标志未生效或其被系统改回，记日志（首击自伤排查依据）。
                    boolean own = TapService.inOwnGesture();
                    android.util.Log.i("AutoClicker", "target-touch: running own=" + own
                            + " xy=" + (int) ev.getRawX() + "," + (int) ev.getRawY());
                    if (!own) stopClicking();
                    return true;
                }
                mDownX = ev.getRawX(); mDownY = ev.getRawY();
                mDragStartX = mTargetLp.x; mDragStartY = mTargetLp.y;
                mDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mState == STATE_RUNNING) return true;
                float dx = ev.getRawX() - mDownX, dy = ev.getRawY() - mDownY;
                if (!mDragging && (Math.abs(dx) > mSlop || Math.abs(dy) > mSlop)) mDragging = true;
                if (mDragging) {
                    mTargetLp.x = mDragStartX + (int) dx;
                    mTargetLp.y = mDragStartY + (int) dy;
                    clampLp(mTargetLp, mTgtSize, mTgtSize);
                    mWm.updateViewLayout(v, mTargetLp);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) savePos();
                mDragging = false;
                return true;
        }
        return true;
    };

    /** 执行态触摸策略：靶标穿透（FLAG_NOT_TOUCHABLE）+ 屏幕常亮（FLAG_KEEP_SCREEN_ON）。 */
    private void setRunningWindowFlags(boolean running) {
        if (!mOverlaysAdded) return;
        try {
            if (running) {
                mTargetLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                mSettingsLp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                mTargetLp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            } else {
                mTargetLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                mSettingsLp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                mTargetLp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            }
            mWm.updateViewLayout(mTarget, mTargetLp);
            mWm.updateViewLayout(mSettingsBtn, mSettingsLp);
        } catch (Exception ig) {}
    }

    public void requestStop() {
        stopClicking();
        updateForeground("已停止");
    }

    // ============ 设置面板（overlay 窗口，点外部退出） ============

    /** 全屏遮罩 + 居中设置面板。窗口可聚焦（否则输入法不弹）。 */
    private void showSettingsPanel() {
        if (mPanelRoot != null) return;
        WindowManager.LayoutParams lp = noFit(new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                mSettingsLp.type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0x66000000);
        root.setClickable(true);
        root.setOnClickListener(v -> dismissSettingsPanel());
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, kc, ev) -> {
            if (kc == android.view.KeyEvent.KEYCODE_BACK && ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                dismissSettingsPanel();
                return true;
            }
            return false;
        });

        LinearLayout panel = buildSettingsPanel();
        panel.setClickable(true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                (int) dp(316), ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(panel, pp);

        mPanelRoot = root;
        try {
            mWm.addView(root, lp);
        } catch (Exception e) {
            mPanelRoot = null;
            toast("设置面板打开失败：" + e.getMessage());
        }
    }

    private void dismissSettingsPanel() {
        if (mPanelRoot != null) {
            try { mWm.removeView(mPanelRoot); } catch (Exception ig) {}
            mPanelRoot = null;
        }
    }

    /** 构建设置面板：连点时间(秒) / 连点频次(次数) / 循环轮次，实时换算摘要。 */
    private LinearLayout buildSettingsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) dp(22), (int) dp(18), (int) dp(22), (int) dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7FBF6);
        bg.setCornerRadius(dp(18));
        panel.setBackground(bg);

        // 标题行：标题居左 + 「退出悬浮控件」居右上角
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("连点设置");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF16331C);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView exit = new TextView(this);
        exit.setText("退出悬浮控件");
        exit.setTextSize(12);
        exit.setTextColor(0xFFB3564E);
        exit.setPadding((int) dp(10), (int) dp(6), 0, (int) dp(6));
        exit.setOnClickListener(v -> {
            dismissSettingsPanel();
            stopSelf();
        });
        titleRow.addView(exit);
        panel.addView(titleRow, panelLp(0));

        EditText dur = panelInput("连点持续的时间（秒）", String.valueOf(mPrefs.getInt(K_DUR, 30)), false);
        EditText cnt = panelInput("持续时间内的点击次数", String.valueOf(mPrefs.getInt(K_CNT, 10)), false);
        EditText rounds = panelInput("循环轮数（0 = 只跑一轮）", String.valueOf(mPrefs.getInt(K_ROUNDS, 0)), false);

        panel.addView(panelLabel("连点时间（点击持续的时间，秒）"), panelLp(10));
        panel.addView(dur, panelLp(4));
        panel.addView(panelLabel("连点频次（持续时间内点击的次数）"), panelLp(8));
        panel.addView(cnt, panelLp(4));
        panel.addView(panelLabel("循环轮次（0 = 只跑一轮）"), panelLp(8));
        panel.addView(rounds, panelLp(4));

        final TextView summary = new TextView(this);
        summary.setTextSize(12);
        summary.setTextColor(0xFF1F7A33);
        summary.setLineSpacing(dp(3), 1.0f);
        TextWatcher refresh = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { summary.setText(calcSummary(dur, cnt, rounds)); }
        };
        dur.addTextChangedListener(refresh);
        cnt.addTextChangedListener(refresh);
        rounds.addTextChangedListener(refresh);
        summary.setText(calcSummary(dur, cnt, rounds));
        panel.addView(summary, panelLp(10));

        TextView hint = new TextView(this);
        hint.setText("安全提示：执行中您随时触摸屏幕、或靶标位置内容变化，连点都会立即停止；屏幕保持常亮。");
        hint.setTextSize(11);
        hint.setTextColor(0xFF7A8A7C);
        hint.setLineSpacing(dp(3), 1.0f);
        panel.addView(hint, panelLp(6));

        Button start = new Button(this);
        start.setText("▶  开始");
        start.setTextSize(16);
        start.setTypeface(null, Typeface.BOLD);
        GradientDrawable sd = new GradientDrawable();
        sd.setColor(0xFF35C75A);
        sd.setCornerRadius(dp(24));
        start.setBackground(sd);
        start.setTextColor(0xFFFFFFFF);
        final boolean[] confirming = {false};
        start.setOnClickListener(v -> {
            int d, c, r;
            try {
                d = Integer.parseInt(dur.getText().toString().trim());
                c = Integer.parseInt(cnt.getText().toString().trim());
                r = Integer.parseInt(rounds.getText().toString().trim());
            } catch (Exception ex) {
                toast("请输入正整数");
                return;
            }
            if (d < 1 || d > 3600) { toast("连点时间限 1~3600 秒"); return; }
            if (c < 1 || c > 100000) { toast("连点频次限 1~100000 次"); return; }
            if (r < 0 || r > 9999) { toast("循环轮次限 0~9999"); return; }
            if ((long) d * 1000 / c < 50) { toast("频次过高：两次点击至少间隔 50ms（次数 ≤ 时间×20）"); return; }
            long totalSec = (long) d * (r == 0 ? 1 : r);
            if (totalSec > 600 && !confirming[0]) {   // 大额连点二次确认
                confirming[0] = true;
                start.setText("再点一次确认开始（共 " + (totalSec / 60) + " 分钟）");
                mMain.postDelayed(() -> {
                    confirming[0] = false;
                    start.setText("▶  开始");
                }, 8000);
                return;
            }
            confirming[0] = false;
            dismissSettingsPanel();
            startClicking(d, c, r);
        });
        // 开始 / 返回：同一行等宽对齐，中间留 10dp 空隙；返回仅关闭设置页，悬浮钮保留待命
        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(16);
        back.setTypeface(null, Typeface.BOLD);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xFFE7EFE8);
        bd.setCornerRadius(dp(24));
        back.setBackground(bd);
        back.setTextColor(0xFF1F7A33);
        back.setOnClickListener(v -> dismissSettingsPanel());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.addView(start, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = (int) dp(10);
        btnRow.addView(back, blp);
        panel.addView(btnRow, panelLp(12));

        return panel;
    }

    /** 换算摘要：间隔 = 时间/次数。 */
    private String calcSummary(EditText dur, EditText cnt, EditText rounds) {
        try {
            int d = Integer.parseInt(dur.getText().toString().trim());
            int c = Integer.parseInt(cnt.getText().toString().trim());
            int r = Integer.parseInt(rounds.getText().toString().trim());
            if (d < 1 || c < 1 || r < 0) return "";
            long ivMs = (long) d * 1000 / c;
            String iv = ivMs >= 1000
                    ? String.format("%.1f 秒", ivMs / 1000.0) : ivMs + " 毫秒";
            long total = (long) d * (r == 0 ? 1 : r);
            return "换算：每 " + iv + " 点击 1 次 · 单轮 " + d + " 秒内点击 " + c
                    + " 次 · 共 " + (r == 0 ? 1 : r) + " 轮（总约 " + (total >= 60 ? total / 60 + " 分 " + total % 60 + " 秒" : total + " 秒") + "）";
        } catch (Exception e) { return ""; }
    }

    private TextView panelLabel(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(12);
        v.setTextColor(0xFF7A8A7C);
        return v;
    }

    private EditText panelInput(String hint, String val, boolean decimal) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER |
                (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setTextColor(0xFF16331C);
        e.setHintTextColor(0xFFB0BCB1);
        GradientDrawable box = new GradientDrawable();
        box.setColor(0xFFFFFFFF);
        box.setStroke((int) dp(1), 0xFF35C75A);
        box.setCornerRadius(dp(10));
        e.setBackground(box);
        e.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        return e;
    }

    private static LinearLayout.LayoutParams panelLp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    // ============ 执行状态机 ============

    private void startClicking(int seconds, int count, int rounds) {
        TapService ts = TapService.instance();
        if (ts == null) { toast("无障碍服务不可用，无法开始"); return; }

        // 1) 锁点一次：用靶标在屏幕上的真实位置（getLocationOnScreen 与手势坐标同系）
        int[] loc = new int[2];
        int lx, ly;
        if (mTarget != null && mTarget.getWidth() > 0) {
            mTarget.getLocationOnScreen(loc);
            lx = loc[0] + mTarget.getWidth() / 2;
            ly = loc[1] + mTarget.getHeight() / 2;
        } else {                                   // 兜底：窗口未完成布局时按窗口参数推算
            lx = mTargetLp.x + mTgtSize / 2;
            ly = mTargetLp.y + mTgtSize / 2;
        }
        lx = Math.max(mSafeL, Math.min(lx, mSafeR - 1));
        ly = Math.max(mSafeT, Math.min(ly, mSafeB - 1));
        int m = (int) dp(10);
        if (hitRect(mSettingsLp, mBtnSize, m, lx, ly)) {
            toast("设置按钮与靶标重叠，请先拖开设置按钮");
            return;
        }

        // 坐标越界校验：锁点必须在真实屏幕内，越界的坐标手势会被系统整单拒绝
        if (lx < 0 || ly < 0 || lx >= mScreenW || ly >= mScreenH) {
            toast("靶标位置异常(" + lx + "," + ly + ")，请重新拖动靶标");
            return;
        }

        // 2) 参数：间隔 = 连点时间 / 次数；0 轮 = 1 轮
        long intervalMs = Math.max(50, (long) seconds * 1000 / count);
        TapService.Params p = new TapService.Params(intervalMs, count,
                rounds == 0 ? 1 : rounds, lx, ly);
        android.util.Log.i("AutoClicker", "startClicking: pt=" + lx + "," + ly
                + " screen=" + mScreenW + "x" + mScreenH
                + " iv=" + intervalMs + "ms taps=" + count + " rounds=" + (rounds == 0 ? 1 : rounds));

        mPrefs.edit().putInt(K_DUR, seconds).putInt(K_CNT, count)
                .putInt(K_ROUNDS, rounds).apply();

        // 3) 先进执行态 + 提交窗口穿透标志，再启动引擎。
        //    顺序不可颠倒：引擎第一下点击派发需几毫秒，若穿透标志晚于它生效，
        //    第一击会落进自己的靶标窗口（WM relayout 有延迟；TapService 另有 120ms 首击延迟兜底）。
        mState = STATE_RUNNING;
        mSettingsBtn.setRunning(true);
        setRunningWindowFlags(true);   // 靶标穿透 + 屏幕常亮（同步提交给 WindowManager）

        ts.setListener(tapListener);
        if (!ts.startTap(p)) {
            android.util.Log.w("AutoClicker", "startTap FAILED: instance=" + ts.instance()
                    + " iv=" + intervalMs + " taps=" + count + " rounds=" + (rounds == 0 ? 1 : rounds));
            mState = STATE_IDLE;
            mSettingsBtn.setRunning(false);
            setRunningWindowFlags(false);
            toast("启动失败，请检查无障碍服务");
            return;
        }

        updateForeground("正在连点 · 每 " + fmtIv(intervalMs) + " 一次");
        startBlink();
        // 兜底：上一会话若有残留海绵（拖停后未抬手就重新开始等极端时序），先强制清掉
        mSpongeTouching = false;
        removeSponge();
        // 海绵延迟 300ms 再挂，错开首击（120ms 派发）：ai 实测海绵 addView 后 ~130ms 内
        // 注入首击被尚未同步完成的窗口截住 → 点击被吞 + DOWN 分支当真手停机。
        if (mSpongePending != null) mMain.removeCallbacks(mSpongePending);
        if (mSpongeRemovePending != null) mMain.removeCallbacks(mSpongeRemovePending);
        final int sx = lx, sy = ly;
        mSpongePending = () -> {
            if (mState == STATE_RUNNING && !mSpongeAdded) addSponge(sx, sy);
        };
        mMain.postDelayed(mSpongePending, 300);
    }

    private void stopClicking() { stopClicking(null); }

    private void stopClicking(String msg) {
        if (mState != STATE_RUNNING) return;
        mState = STATE_IDLE;
        TapService ts = TapService.instance();
        if (ts != null) {
            ts.setListener(null);
            ts.stopTap();
        }
        if (mSettingsBtn != null) mSettingsBtn.setRunning(false);
        setRunningWindowFlags(false);
        stopBlink();
        if (mSpongePending != null) mMain.removeCallbacks(mSpongePending);
        scheduleSpongeRemoval();
        updateForeground("已停止");
        if (msg != null) toast(msg);
    }

    /** 停止后海绵延迟 1.5s 再拆（an）。背景：采样通道恢复「单次差异即停」（an 复盘：
     *  am 的稳定确认在闪烁页面上永远凑不齐 → 换页兜底通道瘫痪），页面闪烁仍可能自动
     *  停引擎——海绵若随停即拆，用户紧接着的触摸直达应用 = 误点（al 实测定案）。
     *  延迟拆除让停止后 1.5s 内的触摸被海绵吸收，误点从机制上根治；手指已在海绵上
     *  （触摸即停/拖停场景）维持原逻辑，抬手由 sponge-up 拆除。 */
    private void scheduleSpongeRemoval() {
        if (mSpongeTouching) { removeSponge(); return; }   // 抬手时 sponge-up 会拆
        if (mSpongeRemovePending != null) mMain.removeCallbacks(mSpongeRemovePending);
        mSpongeRemovePending = () -> { if (!mSpongeTouching) removeSponge(); };
        mMain.postDelayed(mSpongeRemovePending, 1500);
    }

    // ============ 海绵监听层：摸到即停 / 拖动换位 ============

    /** overlay 窗口一律禁 insets fit：ColorOS 对 overlay 默认做「避开状态栏」适配
     *  （fitTypes=NAVIGATION_BARS/CAPTION_BAR），顶部窗口被整体下移 140px——
     *  ai/aj 两轮「吞击/自停」的真凶（sponge-frame0: onScreen=0,140 want=0,0 实锤）。
     *  本应用的 overlay 全部使用屏幕绝对坐标，必须与屏幕原点对齐；FLAG_LAYOUT_NO_LIMITS
     *  兜底 API<30。 */
    private static WindowManager.LayoutParams noFit(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        return lp;
    }

    /** 执行期挂海绵：4 块可触摸矩形铺满全屏，只在锁点周围留 ±2dp 小孔。
     *  自动点击只从孔里穿过 → 「摸到海绵」本身就是 100% 用户指纹，无需任何判别
     *  （v1.0.0ai 定案：ColorOS 触摸副本不可复原，aa~ah 的全部「旁听+判别」方案作废）。 */
    private void addSponge(int cx, int cy) {
        if (mSpongeAdded) return;
        int hole = (int) dp(SPONGE_HOLE_DP);       // 小孔半宽（±2dp≈±7px，自动点击必经）
        int l = cx - hole, t = cy - hole, r = cx + hole, b = cy + hole;
        int[][] rects = {
                {0, 0, mScreenW, t},               // 上：全宽 × 孔上沿
                {0, b, mScreenW, mScreenH - b},    // 下
                {0, t, l, b - t},                  // 左：孔左边
                {r, t, mScreenW - r, b - t},       // 右
        };
        for (int i = 0; i < 4; i++) {
            int x = Math.max(0, rects[i][0]), y = Math.max(0, rects[i][1]);
            int w = Math.min(rects[i][2], mScreenW - x), h = Math.min(rects[i][3], mScreenH - y);
            if (w <= 0 || h <= 0) { mSpongeViews[i] = null; continue; }
            View v = new View(this);
            v.setOnTouchListener(spongeTouch);
            WindowManager.LayoutParams lp = noFit(new WindowManager.LayoutParams(w, h,
                    mSettingsLp.type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT));
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = x; lp.y = y;
            final int fi = i, fx = x, fy = y;
            try {
                mWm.addView(v, lp); mSpongeViews[i] = v;
                // 帧级取证：打印窗口实际落屏位置，与期望值对账（鉴别 ColorOS 定位偏移）
                v.post(() -> {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    android.util.Log.i("AutoClicker", "sponge-frame" + fi + ": onScreen=" + loc[0] + "," + loc[1]
                            + " want=" + fx + "," + fy);
                });
            }
            catch (Exception e) { mSpongeViews[i] = null; }
            android.util.Log.i("AutoClicker", "sponge-rect" + i + ": " + x + "," + y
                    + " " + w + "x" + h + " hole=" + cx + "," + cy + "±" + hole);
        }
        mSpongeAdded = true;
    }

    /** 拆海绵。手指还在屏上时延迟（否则拖动/吞触中途窗口被拆、手势中断）。 */
    private void removeSponge() {
        if (mSpongeTouching) return;
        for (int i = 0; i < 4; i++) {
            if (mSpongeViews[i] != null) {
                try { mWm.removeView(mSpongeViews[i]); } catch (Exception ig) {}
                mSpongeViews[i] = null;
            }
        }
        mSpongeAdded = false;
    }

    private final View.OnTouchListener spongeTouch = (v, ev) -> {
        // 注入手势豁免（双条件，缺一不可）：
        // ① inOwnGesture()：手势在途 + 135ms 尾差窗（al 教训：6Hz 时该窗盖住 81% 时间轴，
        //    真手 DOWN 大概率撞上被误豁免 → 「点 2~3 次才停」，与 ad 时代时间窗教训同源）；
        // ② 落点在锁点 ±2dp 孔内：注入事件坐标=锁点精确值（aj/ak 日志实测 ±0.1px），
        //    真手不可能精确落孔心。二者同时成立才豁免 → 真手无论何时按下都立即停。
        if (TapService.inOwnGesture()
                && TapService.nearTapPoint(ev.getRawX(), ev.getRawY(), SPONGE_HOLE_DP)) {
            android.util.Log.i("AutoClicker", "sponge-inject: own gesture ignored ("
                    + ev.getActionMasked() + ") xy=" + (int) ev.getRawX() + "," + (int) ev.getRawY());
            return true;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mSpongeTouching = true;
                mDownX = ev.getRawX(); mDownY = ev.getRawY();
                mDragStartX = mTargetLp.x; mDragStartY = mTargetLp.y;
                mDragging = false;
                // 海绵只存在于执行期：摸到 = 用户触摸（自动点击只走小孔），一律 DOWN 即停。
                // 锁点 80dp 内额外允许拖动换位（mDragCapable：超 slop 后靶标跟手）。
                mDragCapable = TapService.nearTapPoint(mDownX, mDownY, SPONGE_DRAG_DP);
                boolean onBtn = hitRect(mSettingsLp, mBtnSize, 0, (int) mDownX, (int) mDownY);
                android.util.Log.i("AutoClicker", "sponge-down: xy=" + (int) mDownX + "," + (int) mDownY
                        + " dragCap=" + mDragCapable + " onBtn=" + onBtn + " state=" + mState);
                if (mState == STATE_RUNNING) {
                    stopClicking(onBtn ? "已停止" : "检测到手动触摸，已停止");
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mDragCapable) return true;   // 非抓靶区滑动不挪靶标（停止后顺手的滑动不改变落点）
                float dx = ev.getRawX() - mDownX, dy = ev.getRawY() - mDownY;
                if (!mDragging && (Math.abs(dx) > mSlop || Math.abs(dy) > mSlop)) {
                    mDragging = true;
                    // 引擎在 DOWN 时已停；海绵因手指在屏延迟拆除，拖动不受影响
                    android.util.Log.i("AutoClicker", "sponge-drag: relocate");
                }
                if (mDragging) {   // 靶标跟手走
                    mTargetLp.x = mDragStartX + (int) dx;
                    mTargetLp.y = mDragStartY + (int) dy;
                    clampLp(mTargetLp, mTgtSize, mTgtSize);
                    try { mWm.updateViewLayout(mTarget, mTargetLp); } catch (Exception ig) {}
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasDrag = mDragging;
                mDragging = false;
                mSpongeTouching = false;
                android.util.Log.i("AutoClicker", "sponge-up: drag=" + wasDrag + " state=" + mState);
                if (wasDrag) savePos();   // 靶标停在新位置，下一轮点新位置
                else if (mState == STATE_RUNNING) stopClicking("检测到手动触摸，已停止");
                removeSponge();
                return true;
        }
        return true;
    };

    private static boolean hitRect(WindowManager.LayoutParams lp, int size, int pad, int x, int y) {
        return x >= lp.x - pad && x <= lp.x + size + pad && y >= lp.y - pad && y <= lp.y + size + pad;
    }

    private static String fmtIv(long ms) {
        return ms >= 1000 ? String.format("%.1fs", ms / 1000.0) : ms + "ms";
    }

    private final TapService.Listener tapListener = new TapService.Listener() {
        @Override public void onRoundDone(long done, long total) {
            updateForeground("正在连点 · 第 " + done + " / " + total + " 轮");
        }
        @Override public void onFinished(boolean natural, String reason) {
            mState = STATE_IDLE;
            if (mSettingsBtn != null) mSettingsBtn.setRunning(false);
            setRunningWindowFlags(false);
            stopBlink();
            removeSponge();   // 引擎自行结束（跑完/取消/息屏）时同步拆海绵（手指在屏则待抬手）

            String msg, ntf;
            if (natural) { msg = "连点完成"; ntf = "已完成"; }
            else if (TapService.R_TOUCH.equals(reason)) { msg = "检测到手动触摸，已停止"; ntf = "已停止（手动触摸）"; }
            else if (TapService.R_TARGET.equals(reason)) { msg = "靶标位置已变化，已停止"; ntf = "已停止（靶标变化）"; }
            else if (TapService.R_SCREEN.equals(reason)) { msg = "屏幕熄灭，已停止"; ntf = "已停止（息屏）"; }
            else if (TapService.R_FAILS.equals(reason)) { msg = "连续派发失败，已自动停止"; ntf = "已停止（异常）"; }
            else { msg = "连点已停止"; ntf = "已停止"; }
            updateForeground(ntf);
            toast(msg);
        }
    };

    // ============ 视觉控件（简约 绿+白） ============

    private static final int C_GREEN = 0xFF39FF14;
    private static final int C_GRAY  = 0xFF9AA5A0;
    private static final int C_WHITE = 0xFFFFFFFF;

    abstract static class RoundView extends View {
        private final int mSize;
        RoundView(Context c, int sizeDp) {
            super(c);
            mSize = (int) (sizeDp * c.getResources().getDisplayMetrics().density);
        }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(mSize, mSize); }
    }

    /** 设置/停止钮 48dp（与桌面图标同级）：待命=白底绿环+绿色齿轮⚙（仅设置入口）；执行=红底+白色方块（停止）。 */
    class SettingsControl extends RoundView {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mRunning;

        SettingsControl(Context c) { super(c, 48); }

        void setRunning(boolean r) { mRunning = r; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float d = getWidth(), cx = d / 2f, cy = d / 2f;
            float r = d / 2f - dp(1);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mRunning ? 0xFFFF3B30 : 0xF2FFFFFF);
            cv.drawCircle(cx, cy, r, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(mRunning ? 2.2f : 1.5f));
            mPaint.setColor(mRunning ? 0xFFE03A31 : C_GREEN);
            cv.drawCircle(cx, cy, r, mPaint);
            if (mRunning) {
                // 执行：白色停止方块
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(Color.WHITE);
                float s = d * 0.26f;
                cv.drawRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, mPaint);
            } else {
                // 待命：绿色齿轮（设置图标，非开始键）
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setColor(0xFF35C75A);
                float gr = d * 0.17f;
                mPaint.setStrokeWidth(dp(2));
                cv.drawCircle(cx, cy, gr, mPaint);
                mPaint.setStrokeWidth(dp(2.2f));
                for (int i = 0; i < 8; i++) {
                    cv.save();
                    cv.rotate(i * 45f, cx, cy);
                    cv.drawLine(cx, cy - gr - dp(0.8f), cx, cy - gr - dp(3.4f), mPaint);
                    cv.restore();
                }
                mPaint.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy, dp(1.6f), mPaint);
            }
        }
    }

    /** 靶标 24dp：圆环 + 中心点。执行时环色绿↔灰呼吸，且整体不拦截触摸。 */
    class TargetControl extends RoundView {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mRingColor = C_GREEN;

        TargetControl(Context c) { super(c, 24); }

        void setRingColor(int c) { mRingColor = c; invalidate(); }

        void resetRing() { mRingColor = C_GREEN; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float d = getWidth(), cx = d / 2f, cy = d / 2f;
            float r = d / 2f - dp(0.5f);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0x24FFFFFF);
            cv.drawCircle(cx, cy, r, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(1.4f));
            mPaint.setColor(mRingColor);
            cv.drawCircle(cx, cy, r * 0.82f, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(C_WHITE);
            cv.drawCircle(cx, cy, d * 0.16f, mPaint);
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private void startBlink() {
        stopBlink();
        mBlink = ObjectAnimator.ofArgb(mTarget, "ringColor", C_GREEN, C_GRAY);
        mBlink.setDuration(900);
        mBlink.setRepeatMode(ValueAnimator.REVERSE);
        mBlink.setRepeatCount(ValueAnimator.INFINITE);
        mBlink.start();
    }

    private void stopBlink() {
        if (mBlink != null) { mBlink.cancel(); mBlink = null; }
        if (mTarget != null) { mTarget.resetRing(); mTarget.setAlpha(1f); }
    }

    // ============ 屏幕安全区 / 通知 ============

    private void computeSafeArea() {
        DisplayMetrics dm = new DisplayMetrics();
        mWm.getDefaultDisplay().getRealMetrics(dm);
        mScreenW = dm.widthPixels; mScreenH = dm.heightPixels;
        int sb = sysDimen("status_bar_height");
        int nb = sysDimen("navigation_bar_height");
        int m = (int) dp(10);
        mSafeL = m;
        mSafeT = sb + m;
        mSafeR = mScreenW - m;
        mSafeB = mScreenH - nb - m;
        if (mSafeT < m) mSafeT = m;
        if (mSafeB <= mSafeT) mSafeB = mScreenH - m;
    }

    private int sysDimen(String name) {
        android.content.res.Resources res = getResources();
        int id = res.getIdentifier(name, "dimen", "android");
        if (id == 0) return 0;
        try { return res.getDimensionPixelSize(id); } catch (Exception e) { return 0; }
    }

    private NotificationManager nm() { return (NotificationManager) getSystemService(NOTIFICATION_SERVICE); }

    private void startForegroundCompat(String text) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("tap", "连点状态", NotificationManager.IMPORTANCE_LOW);
                nm().createNotificationChannel(ch);
            }
            startForeground(NOTIF_ID, buildNotif(text));
        } catch (Exception e) {
            // 个别 ROM 异常：前台服务保底启动
        }
    }

    private Notification buildNotif(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // 通知栏「停止」：完全不依赖悬浮窗的兜底停止通道
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "tap")
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("L1Tap")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "停止", ps).build());
        return b.build();
    }

    private void updateForeground(String text) {
        try { nm().notify(NOTIF_ID, buildNotif(text)); } catch (Exception ig) {}
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
