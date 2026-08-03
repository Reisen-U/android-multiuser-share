package com.example.multiusershare;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 77;
    private static final String PROJECT_URL = "https://github.com/Reisen-U/android-multiuser-share";
    private TextView statusView;
    private TextView addressView;
    private TextView qrHint;
    private TextView dataInfo;
    private QrCodeView qrView;
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText portInput;
    private Switch authSwitch;
    private Button startButton;
    private Button stopButton;
    private final ExecutorService dataExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateStatus(intent.getStringExtra(ShareService.EXTRA_STATE), intent.getStringExtra(ShareService.EXTRA_ADDRESS));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ShareService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        refreshFromService();
    }

    @Override protected void onStop() {
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    @Override protected void onDestroy() {
        dataExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ConfigStore config = new ConfigStore(this);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        TextView appBar = text("HTTP共享", 20, Color.WHITE);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appBar.setPadding(dp(20), 0, dp(20), 0);
        appBar.setBackgroundColor(Color.rgb(13, 71, 161));
        appBar.setElevation(dp(4));
        screen.addView(appBar, lp(-1, dp(56)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            int bottomInset = insets.getSystemWindowInsetBottom();
            appBar.setPadding(dp(20), topInset, dp(20), 0);
            LinearLayout.LayoutParams appBarParams = (LinearLayout.LayoutParams) appBar.getLayoutParams();
            appBarParams.height = dp(56) + topInset;
            appBar.setLayoutParams(appBarParams);
            root.setPadding(dp(20), dp(18), dp(20), dp(28) + bottomInset);
            return insets;
        });

        TextView subtitle = text("在本机启动一个局域网共享站，其他设备通过浏览器即可访问。", 15, Color.DKGRAY);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle, lp(-1, -2));

        statusView = text("服务状态：未启动", 17, Color.rgb(40, 40, 40));
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusView.setBackgroundColor(Color.rgb(235, 241, 249));
        root.addView(statusView, lp(-1, -2));

        addressView = text("启动后显示局域网地址", 15, Color.rgb(30, 30, 30));
        addressView.setPadding(0, dp(14), 0, dp(4));
        root.addView(addressView, lp(-1, -2));

        qrView = new QrCodeView(this);
        qrView.setVisibility(View.GONE);
        root.addView(qrView, lp(-1, dp(220)));
        qrHint = text("二维码仅在服务运行时显示；浏览器也可直接输入地址。", 12, Color.GRAY);
        qrHint.setGravity(Gravity.CENTER);
        qrHint.setVisibility(View.GONE);
        root.addView(qrHint, lp(-1, -2));

        LinearLayout actions = row();
        startButton = new Button(this);
        startButton.setText("启动共享");
        stopButton = new Button(this);
        stopButton.setText("停止共享");
        actions.addView(startButton, weightLp(1));
        actions.addView(stopButton, weightLp(1));
        root.addView(actions, lp(-1, -2));

        root.addView(sectionTitle("参数设置"), lp(-1, -2));
        usernameInput = field("请输入用户名", config.username(), false);
        passwordInput = field("至少 12 位", config.password(), true);
        portInput = field("1024-65535", String.valueOf(config.port()), false);

        root.addView(labeledFieldRow("用户名", usernameInput, null), lp(-1, -2));

        CheckBox showPassword = new CheckBox(this);
        showPassword.setText("显示密码");
        showPassword.setTextSize(14);
        showPassword.setSingleLine(true);
        showPassword.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int selection = passwordInput.getSelectionStart();
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | (isChecked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordInput.setTypeface(Typeface.DEFAULT);
            passwordInput.setSelection(Math.max(0, Math.min(selection, passwordInput.length())));
        });
        root.addView(labeledFieldRow("密码", passwordInput, showPassword), lp(-1, -2));
        root.addView(labeledFieldRow("端口", portInput, null), lp(-1, -2));

        authSwitch = new Switch(this);
        authSwitch.setText("启用密码保护（推荐）");
        authSwitch.setTextSize(16);
        authSwitch.setChecked(config.authEnabled());
        LinearLayout.LayoutParams authParams = lp(-1, -2);
        authParams.setMargins(0, dp(12), 0, 0);
        root.addView(authSwitch, authParams);
        TextView warning = text("公开访问时，同一 Wi-Fi 的设备也可以访问。服务使用 HTTP，不适合传输高度敏感内容。", 13, Color.rgb(173, 89, 0));
        warning.setPadding(0, dp(4), 0, dp(12));
        root.addView(warning, lp(-1, -2));

        root.addView(sectionTitle("数据管理"), lp(-1, -2));
        dataInfo = text("共享文本和文件保存在本应用私有目录中。服务重启或升级不会删除数据。", 14, Color.DKGRAY);
        root.addView(dataInfo, lp(-1, -2));
        Button openData = new Button(this);
        openData.setText("打开共享页面");
        openData.setOnClickListener(v -> {
            String address = ShareService.currentAddress;
            if (address == null || address.isEmpty()) {
                Toast.makeText(this, "请先启动共享服务", Toast.LENGTH_SHORT).show();
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address)));
            }
        });
        root.addView(openData, lp(-1, -2));

        View footerDivider = new View(this);
        footerDivider.setBackgroundColor(Color.rgb(218, 223, 230));
        LinearLayout.LayoutParams dividerParams = lp(-1, dp(1));
        dividerParams.setMargins(dp(48), dp(22), dp(48), 0);
        root.addView(footerDivider, dividerParams);

        TextView projectLink = text("GitHub 项目主页\n" + PROJECT_URL, 13, Color.rgb(13, 71, 161));
        projectLink.setGravity(Gravity.CENTER);
        projectLink.setPadding(0, dp(12), 0, dp(4));
        projectLink.setLineSpacing(dp(3), 1f);
        projectLink.setContentDescription("在浏览器中打开项目 GitHub 地址");
        projectLink.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PROJECT_URL))));
        root.addView(projectLink, lp(-1, -2));

        startButton.setOnClickListener(v -> startSharing());
        stopButton.setOnClickListener(v -> stopSharing());
        authSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) Toast.makeText(this, "公开访问风险较高，请仅在可信网络使用", Toast.LENGTH_LONG).show();
        });
        setContentView(screen);
        screen.requestApplyInsets();
    }

    private void startSharing() {
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        boolean auth = authSwitch.isChecked();
        int port;
        try { port = Integer.parseInt(portInput.getText().toString().trim()); }
        catch (Exception e) { Toast.makeText(this, "端口必须是数字", Toast.LENGTH_SHORT).show(); return; }
        if (username.isEmpty()) { Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show(); return; }
        if (port < 1024 || port > 65535) { Toast.makeText(this, "端口范围为 1024-65535", Toast.LENGTH_SHORT).show(); return; }
        if (auth && password.length() < 12) { Toast.makeText(this, "密码保护至少需要 12 位密码", Toast.LENGTH_SHORT).show(); return; }
        new ConfigStore(this).save(username, password, auth, port);
        Intent intent = new Intent(this, ShareService.class).setAction(ShareService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        updateStatus("STARTING", "");
    }

    private void stopSharing() {
        startService(new Intent(this, ShareService.class).setAction(ShareService.ACTION_STOP));
        updateStatus("STOPPED", "");
    }

    private void refreshFromService() {
        if (dataInfo != null) {
            dataInfo.setText("共享文本和文件保存在本应用私有目录中。服务重启或升级不会删除数据。\n正在统计占用空间...");
            dataExecutor.execute(() -> {
                String size = formatBytes(directorySize(getFilesDir()));
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        dataInfo.setText("共享文本和文件保存在本应用私有目录中。服务重启或升级不会删除数据。\n当前占用：" + size);
                    }
                });
            });
        }
        updateStatus(ShareService.currentState, ShareService.currentAddress);
    }

    private long directorySize(java.io.File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        java.io.File[] children = file.listFiles();
        if (children != null) for (java.io.File child : children) total += directorySize(child);
        return total;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024d);
        if (bytes < 1024 * 1024 * 1024L) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024d / 1024d);
        return String.format(Locale.getDefault(), "%.1f GB", bytes / 1024d / 1024d / 1024d);
    }

    private void updateStatus(String state, String address) {
        if (state == null) state = "STOPPED";
        String label;
        if ("RUNNING".equals(state)) label = "服务状态：运行中";
        else if ("STARTING".equals(state)) label = "服务状态：启动中";
        else if ("FAILED".equals(state)) label = "服务状态：启动失败";
        else label = "服务状态：未启动";
        statusView.setText(label);
        boolean running = "RUNNING".equals(state);
        startButton.setEnabled(!running);
        stopButton.setEnabled(running || "STARTING".equals(state));
        if (running && address != null && !address.isEmpty()) {
            addressView.setText(String.format(Locale.getDefault(), "访问地址：%s\n可复制到另一用户的浏览器，或在同一 Wi-Fi 设备访问。", address));
            qrView.setVisibility(View.VISIBLE);
            qrHint.setVisibility(View.VISIBLE);
            qrView.setValue(address);
        } else {
            addressView.setText("启动后显示局域网地址");
            qrView.setVisibility(View.GONE);
            qrHint.setVisibility(View.GONE);
        }
    }

    private EditText field(String hint, String value, boolean password) {
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(0, dp(6), 0, dp(6));
        if (password) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private LinearLayout labeledFieldRow(String labelText, EditText field, View trailingView) {
        LinearLayout fieldRow = row();
        fieldRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = text(labelText, 16, Color.rgb(45, 45, 45));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setLabelFor(field.getId());
        fieldRow.addView(label, lp(dp(64), -1));
        fieldRow.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailingView != null) fieldRow.addView(trailingView, lp(-2, -2));
        return fieldRow;
    }

    private TextView sectionTitle(String title) {
        TextView view = text(title, 18, Color.rgb(13, 71, 161));
        view.setPadding(0, dp(22), 0, dp(8));
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private LinearLayout.LayoutParams weightLp(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, weight);
        p.setMargins(dp(4), dp(2), dp(4), dp(2));
        return p;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
