package com.l1.autoclicker;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * 连点器主界面：权限引导（悬浮窗 + 无障碍）与使用说明。
 * 点"启动悬浮控件"后拉起 OverlayService，两个悬浮球常驻。
 */
public class MainActivity extends Activity {

    private TextView mOvStatus, mAcStatus;
    private Button mOvBtn, mAcBtn, mLaunch;

    private static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        // Android 13+ 通知权限：应用内一次性申请（执行中通知栏「停止」按钮依赖它显示）
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        // 切走页面/回桌面：立即停止连点（悬浮球与设置钮保持）
        OverlayService s = OverlayService.instance;
        if (s != null) s.requestStop();
    }

    private void refresh() {
        boolean ov = Settings.canDrawOverlays(this);
        boolean ac = serviceEnabled();
        mOvStatus.setText(ov ? "✔ 已开启" : "✘ 未开启");
        mOvStatus.setTextColor(ov ? 0xFF39FF14 : 0xFFFF3B30);
        mOvBtn.setBackground(btn(ov ? 0xFF1F2A1F : 0xFF39FF14));
        mOvBtn.setTextColor(ov ? 0xFF8FA08F : 0xFF0E0E0E);
        mOvBtn.setText(ov ? "已开启" : "去开启");
        mOvBtn.setEnabled(!ov);

        mAcStatus.setText(ac ? "✔ 已开启" : "✘ 未开启");
        mAcStatus.setTextColor(ac ? 0xFF39FF14 : 0xFFFF3B30);
        mAcBtn.setBackground(btn(ac ? 0xFF1F2A1F : 0xFF39FF14));
        mAcBtn.setTextColor(ac ? 0xFF8FA08F : 0xFF0E0E0E);
        mAcBtn.setText(ac ? "已开启" : "去开启");
        mAcBtn.setEnabled(!ac);

        mLaunch.setBackground(btn(ov && ac ? 0xFF39FF14 : 0xFF1F2A1F));
        mLaunch.setTextColor(0xFF0E0E0E);
        mLaunch.setEnabled(ov && ac);
    }

    private boolean serviceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> l = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo i : l) {
            if (getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }

    private View buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF0E0E0E);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) dp(this, 24), (int) dp(this, 40), (int) dp(this, 24), (int) dp(this, 24));
        sc.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("连点器");
        title.setTextSize(30);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFE8F5E9);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(0, (int) dp(this, 8)));

        TextView sub = new TextView(this);
        sub.setText("悬浮两个小控件：⚙ 设置/停止（48dp） · ◎ 靶标（24dp，可拖动）");
        sub.setTextSize(13);
        sub.setTextColor(0xFF8FA08F);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, lp((int) dp(this, 18), 0));

        // ===== 悬浮窗权限卡 =====
        LinearLayout ovCard = card();
        TextView ovTitle = t("① 悬浮窗权限", 16, 0xFFE8F5E9);
        ViewGroup.LayoutParams tl = lp(0, 4);
        ovCard.addView(ovTitle, tl);
        ovCard.addView(t("让两个控件悬浮显示在其它应用之上，可自由拖动。", 12, 0xFF8FA08F), lp(0, 4));
        LinearLayout ovRow = new LinearLayout(this);
        ovRow.setGravity(Gravity.CENTER_VERTICAL);
        mOvStatus = t("", 14, 0xFF39FF14);
        mOvBtn = rowBtn("去开启");
        mOvBtn.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        ovRow.addView(mOvStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ovRow.addView(mOvBtn);
        ovCard.addView(ovRow, lp((int) dp(this, 12), 0));
        root.addView(ovCard, lp((int) dp(this, 12), 0));

        // ===== 无障碍权限卡 =====
        LinearLayout acCard = card();
        acCard.addView(t("② 无障碍服务", 16, 0xFFE8F5E9), lp(0, 4));
        acCard.addView(t("点击与安全监测需要系统无障碍授权，由您在本应用内主动开启；执行中仅读取落点附近的控件特征用于「靶标变化即停」，不存储、不上传。", 12, 0xFF8FA08F), lp(0, 4));
        LinearLayout acRow = new LinearLayout(this);
        acRow.setGravity(Gravity.CENTER_VERTICAL);
        mAcStatus = t("", 14, 0xFF39FF14);
        mAcBtn = rowBtn("去开启");
        mAcBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        acRow.addView(mAcStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        acRow.addView(mAcBtn);
        acCard.addView(acRow, lp((int) dp(this, 12), 0));
        root.addView(acCard, lp((int) dp(this, 12), 0));

        // ===== 启动（权限卡正下方） =====
        mLaunch = new Button(this);
        mLaunch.setText("启动悬浮控件");
        mLaunch.setTypeface(null, Typeface.BOLD);
        mLaunch.setTextSize(17);
        mLaunch.setBackground(btn(0xFF1F2A1F));
        mLaunch.setTextColor(0xFF0E0E0E);
        mLaunch.setOnClickListener(v -> {
            Intent i = new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            finish();
        });
        root.addView(mLaunch, lp((int) dp(this, 20), 0));

        // ===== 使用说明 =====
        LinearLayout info = card();
        info.addView(t("使用说明", 14, 0xFFE8F5E9), lp(0, 4));
        String steps = "1) 待命时把 靶标(圈+点) 拖到要连点的位置，中心白点即落点\n"
                + "2) 点 ⚙ 设置钮进入设置页：连点时间(秒) / 连点频次(次数) / 循环轮次(0=只跑一轮)\n"
                + "3) 在设置页点「开始」：面板收起，钮变红 ■，开始连点；屏幕保持常亮\n"
                + "4) 执行中靶标只显示不拦截触摸，您可正常操作屏幕任何位置\n"
                + "5) 停止方式(任一)：触摸屏幕任意处 / 碰红钮 / 通知栏「停止」\n"
                + "   以及自动停止：靶标位置内容变化 / 切换应用 / 息屏 / 跑完轮次";
        TextView st = t(steps, 12, 0xFF8FA08F);
        st.setLineSpacing(dp(this, 4), 1.0f);
        info.addView(st, lp(0, 0));
        root.addView(info, lp((int) dp(this, 12), 0));

        return sc;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = (int) dp(this, 16);
        c.setPadding(p, p, p, p);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF161A16);
        d.setCornerRadius(14);
        c.setBackground(d);
        return c;
    }

    private TextView t(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(dp(this, 2), 1.0f);
        return v;
    }

    private Button rowBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(btn(0xFF39FF14));
        b.setTextColor(0xFF0E0E0E);
        b.setMinWidth((int) dp(this, 92));
        return b;
    }

    private static GradientDrawable btn(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(22);
        return d;
    }

    private static LinearLayout.LayoutParams lp(int top, int extra) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }
}