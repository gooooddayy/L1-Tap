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
import android.os.SystemClock;
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
 * C. 用户一有操作就停：引擎侧监听真实触屏事件（TapService），任意触摸立即停。
 * D. 靶标变化即停：引擎侧每 500ms 比对落点特征/前台应用/屏幕方向。
 * E. 防锁屏：执行中悬浮窗带 FLAG_KEEP_SCREEN_ON（屏幕常亮）；息屏广播立即停。
 * F. 停止通道：红钮 / 触摸屏幕 / 通知栏「停止」/ 息屏 / 切走页面 / 服务被杀。
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

        mSettingsLp = new WindowManager.LayoutParams(sb, sb,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        // 关键：以整屏(含状态栏)为坐标系，与手势派发的绝对坐标一致，否则锁点整体偏移
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // ColorOS 不投递触摸类无障碍事件，「用户一有操作就停」改由本窗口旁听：
                        // 屏幕任意位置的触摸按下都会送来一个 ACTION_OUTSIDE（不拦截、不影响正常操作）
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mSettingsLp.gravity = Gravity.TOP | Gravity.START;
        mTargetLp = new WindowManager.LayoutParams(tb, tb, mSettingsLp.type,
                mSettingsLp.flags, PixelFormat.TRANSLUCENT);
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
    private long mStartedAt;                     // 引擎启动时刻：其后 600ms 内的旁听触摸视为「开始」残余
    private final int mSlop = ViewConfiguration.getTouchSlop();

    private final View.OnTouchListener settingsTouch = (v, ev) -> {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_OUTSIDE:
                // 屏幕任意位置的真实触摸旁听（含我们自己派发的连点，需豁免）：
                // ① inOwnGesture = 自身连点的 150ms 豁免窗 ② 启动后 600ms 内 = 「开始」按钮的残余触摸
                if (mState == STATE_RUNNING && !TapService.inOwnGesture()
                        && SystemClock.uptimeMillis() - mStartedAt > 600) {
                    android.util.Log.i("AutoClicker", "outside-touch: user touch -> STOP");
                    stopClicking("检测到手动触摸，已停止");
                }
                return true;
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
                    // 同样只靠 inOwnGesture() 防自击，不做 deviceId 判别。
                    if (!TapService.inOwnGesture()) stopClicking();
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
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                mSettingsLp.type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
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
        //    第一击会落进自己的靶标窗口（WM relayout 有延迟）。
        mStartedAt = SystemClock.uptimeMillis();
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
        updateForeground("已停止");
        if (msg != null) toast(msg);
    }

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
                .setContentTitle("连点器")
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
