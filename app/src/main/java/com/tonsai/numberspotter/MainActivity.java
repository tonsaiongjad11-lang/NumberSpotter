package com.tonsai.numberspotter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private TextView overlayStatus;
    private TextView captureStatus;
    private Switch vibrationSwitch;
    private SharedPreferences prefs;

    private final ActivityResultLauncher<Intent> captureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent svc = new Intent(this, CaptureService.class);
                    svc.setAction(CaptureService.ACTION_START);
                    svc.putExtra(CaptureService.EXTRA_RESULT_CODE, result.getResultCode());
                    svc.putExtra(CaptureService.EXTRA_RESULT_DATA, result.getData());
                    svc.putExtra(CaptureService.EXTRA_VIBRATE, vibrationSwitch.isChecked());
                    ContextCompat.startForegroundService(this, svc);
                    captureStatus.setText("✓ เริ่มตรวจเลขแล้ว — สลับไปหน้าเกมได้เลย");
                    captureStatus.setTextColor(Color.rgb(28, 126, 62));
                } else {
                    captureStatus.setText("ยังไม่ได้อนุญาตจับภาพหน้าจอ");
                    captureStatus.setTextColor(Color.rgb(180, 45, 45));
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("spotter", MODE_PRIVATE);
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOverlayStatus();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView title = new TextView(this);
        title.setText("Number Spotter 1–50");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(30, 30, 30));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(6));
        root.addView(title, matchWrap());

        TextView desc = new TextView(this);
        desc.setText("วงเลขถัดไปทีละตัว • ไม่กดแทน • ข้าม Countdown 1-2-3 อัตโนมัติ");
        desc.setTextSize(16);
        desc.setTextColor(Color.rgb(90, 90, 90));
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, dp(18));
        root.addView(desc, matchWrap());

        overlayStatus = statusText();
        root.addView(overlayStatus, matchWrap());

        Button overlayBtn = bigButton("1) อนุญาตแสดงทับแอปอื่น");
        overlayBtn.setOnClickListener(v -> openOverlaySettings());
        root.addView(overlayBtn, matchWrap());

        vibrationSwitch = new Switch(this);
        vibrationSwitch.setText("สั่นเมื่อเปลี่ยนไปเลขถัดไป");
        vibrationSwitch.setTextSize(17);
        vibrationSwitch.setChecked(prefs.getBoolean("vibrate", true));
        vibrationSwitch.setPadding(dp(4), dp(14), dp(4), dp(14));
        vibrationSwitch.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("vibrate", checked).apply());
        root.addView(vibrationSwitch, matchWrap());

        Button startBtn = bigButton("2) START — เริ่มตรวจเลข");
        startBtn.setOnClickListener(v -> startCapture());
        root.addView(startBtn, matchWrap());

        Button stopBtn = bigButton("STOP");
        stopBtn.setOnClickListener(v -> {
            Intent svc = new Intent(this, CaptureService.class);
            svc.setAction(CaptureService.ACTION_STOP);
            startService(svc);
            captureStatus.setText("หยุดตรวจเลขแล้ว");
            captureStatus.setTextColor(Color.rgb(90, 90, 90));
        });
        root.addView(stopBtn, matchWrap());

        captureStatus = statusText();
        captureStatus.setText("พร้อมเริ่ม");
        root.addView(captureStatus, matchWrap());

        TextView help = new TextView(this);
        help.setText("วิธีใช้: เปิดสิทธิ์ Overlay → กด START → อนุญาตแชร์หน้าจอ → เข้าเกม\n\nระบบจะรอจนเห็นตาราง 5×5 จริงก่อน จึงเริ่มที่เลข 1 และจะไม่ใช้เลข 1-2-3 ตอน Countdown เป็นคำตอบ");
        help.setTextSize(15);
        help.setTextColor(Color.rgb(80, 80, 80));
        help.setPadding(dp(6), dp(18), dp(6), dp(8));
        root.addView(help, matchWrap());

        setContentView(root);
        refreshOverlayStatus();
    }

    private void startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        captureLauncher.launch(mgr.createScreenCaptureIntent());
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void refreshOverlayStatus() {
        if (overlayStatus == null) return;
        boolean ok = Settings.canDrawOverlays(this);
        overlayStatus.setText(ok ? "✓ สิทธิ์แสดงทับแอปอื่น: พร้อม" : "✕ ยังไม่ได้เปิดสิทธิ์แสดงทับแอปอื่น");
        overlayStatus.setTextColor(ok ? Color.rgb(28,126,62) : Color.rgb(180,45,45));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setMinHeight(dp(58));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(7), 0, dp(7));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView statusText() {
        TextView t = new TextView(this);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
