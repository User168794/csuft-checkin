package com.csuft.checkin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** 主界面：状态卡 + 立即打卡 + 自动开关 + 令牌更新 + 记录 + 运行日志（v2.12 诊断） */
public class MainActivity extends Activity {

    private TextView tvStatus, tvStatusSub, tvAutoTag, tvAuth, tvRecords, tvNext, tvLogPreview;
    private Button btnCheckin, btnRefresh, btnAuth, btnLog;
    private Switch swAuto, swRecheck;
    private boolean firstStart = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvStatusSub = (TextView) findViewById(R.id.tvStatusSub);
        tvAutoTag = (TextView) findViewById(R.id.tvAutoTag);
        tvAuth = (TextView) findViewById(R.id.tvAuth);
        tvRecords = (TextView) findViewById(R.id.tvRecords);
        tvNext = (TextView) findViewById(R.id.tvNext);
        tvLogPreview = (TextView) findViewById(R.id.tvLogPreview);
        btnCheckin = (Button) findViewById(R.id.btnCheckin);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnAuth = (Button) findViewById(R.id.btnAuth);
        btnLog = (Button) findViewById(R.id.btnLog);
        swAuto = (Switch) findViewById(R.id.swAuto);
        swRecheck = (Switch) findViewById(R.id.swRecheck);

        btnAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLoginDialog();
            }
        });

        btnCheckin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runManual();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshUi();
                queryStatusAsync();
            }
        });
        btnLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogDialog();
            }
        });
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Store.setAuto(MainActivity.this, checked);
                CheckinScheduler.scheduleAll(MainActivity.this);
                tvAutoTag.setText("自动：" + (checked ? "开" : "关"));
                Store.addLog(MainActivity.this, "自动开关 → " + (checked ? "开" : "关"));
                Toast.makeText(MainActivity.this,
                        checked ? "已开启每日自动打卡（21:00–22:30 复核）" : "已关闭自动打卡",
                        Toast.LENGTH_SHORT).show();
            }
        });
        swRecheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Store.setRecheck(MainActivity.this, checked);
                CheckinScheduler.scheduleAll(MainActivity.this);
                Store.addLog(MainActivity.this, "复核开关 → " + (checked ? "开" : "关"));
                Toast.makeText(MainActivity.this,
                        checked ? "已开启复核（22:35 通知最终判词）" : "已关闭复核",
                        Toast.LENGTH_SHORT).show();
            }
        });
        ((Button) findViewById(R.id.btnBattery)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyBatteryWhitelist();
            }
        });

        Store.addLog(this, "打开 App");
        Notify.ensure(this);
        requestNotifPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CheckinScheduler.cancelHeartbeat(this);
        if (!firstStart) {
            Store.addLog(this, "前台恢复");
        }
        firstStart = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        CheckinScheduler.scheduleAll(this);
        refreshUi();
        queryStatusAsync();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Store.addLog(this, "退到后台");
        CheckinScheduler.armHeartbeat(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            Store.addLog(this, "关闭 App");
            Notify.closed(this);
        }
    }

    // ---------- 手动打卡 ----------
    private void runManual() {
        if (!Store.isLoggedIn(this)) {
            Toast.makeText(this, "请先完成手机号登录", Toast.LENGTH_LONG).show();
            showLoginDialog();
            return;
        }
        btnCheckin.setEnabled(false);
        tvStatus.setText("打卡中…");
        tvStatusSub.setText("正在执行：查询 → 提交 → 验证");
        new Thread(new Runnable() {
            @Override
            public void run() {
                CheckinCore.Res res;
                boolean needLogin = false;
                try {
                    String token = Auth.getValidToken(MainActivity.this);
                    res = CheckinCore.runOnce(token, true, new CheckinCore.Log() {
                        @Override
                        public void line(final String action, final String result) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvStatusSub.setText(action + "：" + result);
                                }
                            });
                        }
                    });
                } catch (Auth.NeedLogin e) {
                    res = new CheckinCore.Res(false, "登录已失效，请重新登录（手机号+验证码）");
                    needLogin = true;
                } catch (Exception e) {
                    res = new CheckinCore.Res(false, "执行异常：" + e.getMessage());
                }
                final CheckinCore.Res r = res;
                final boolean nl = needLogin;
                Store.setLast(MainActivity.this, r.ok, r.msg);
                Store.addRecord(MainActivity.this, r.ok, r.msg);
                Store.addLog(MainActivity.this, "手动打卡：" + r.msg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnCheckin.setEnabled(true);
                        if (r.ok) {
                            tvStatus.setText(r.msg);
                            tvStatus.setTextColor(getResources().getColor(R.color.ok));
                            tvStatusSub.setText(r.dist > 0
                                    ? String.format(Locale.US, "距宿舍 %.1f 米", r.dist)
                                    : "");
                        } else {
                            tvStatus.setText("打卡未成功");
                            tvStatus.setTextColor(getResources().getColor(R.color.danger));
                            tvStatusSub.setText(r.msg);
                        }
                        refreshUi();
                        Toast.makeText(MainActivity.this, r.msg, Toast.LENGTH_LONG).show();
                        if (nl) {
                            showLoginDialog();
                        }
                    }
                });
            }
        }).start();
    }

    // ---------- 状态刷新 ----------
    private void queryStatusAsync() {
        if (!Store.isLoggedIn(this)) {
            tvStatus.setText("未登录");
            tvStatus.setTextColor(getResources().getColor(R.color.accent));
            tvStatusSub.setText("点「手机号登录」完成一次登录即可（之后自动续期）");
            return;
        }
        if (btnRefresh != null) {
            btnRefresh.setEnabled(false);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                CheckinCore.Res r;
                try {
                    r = CheckinCore.queryStatus(Auth.getValidToken(MainActivity.this));
                } catch (Exception e) {
                    r = new CheckinCore.Res(false, "查询失败：" + e.getMessage());
                }
                final CheckinCore.Res rr = r;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (btnRefresh != null) {
                            btnRefresh.setEnabled(true);
                        }
                        if (rr.ok) {
                            tvStatus.setText(rr.msg);
                            tvStatus.setTextColor(getResources().getColor(R.color.ok));
                            tvStatusSub.setText("每天只允许打卡一次，已打卡自动跳过");
                        } else if (rr.msg.startsWith("查询失败")) {
                            tvStatusSub.setText(rr.msg);
                        } else {
                            tvStatus.setText(rr.msg);
                            tvStatus.setTextColor(getResources().getColor(R.color.line));
                            tvStatusSub.setText("自动班次：21:05 / 21:35 / 22:05 / 22:25");
                        }
                    }
                });
            }
        }).start();
    }

    private void refreshUi() {
        boolean auto = Store.auto(this);
        if (swAuto.isChecked() != auto) {
            swAuto.setChecked(auto);
        }
        tvAutoTag.setText("自动：" + (auto ? "开" : "关"));
        if (swRecheck.isChecked() != Store.recheck(this)) {
            swRecheck.setChecked(Store.recheck(this));
        }
        if (Store.isLoggedIn(this)) {
            tvAuth.setText("已登录：" + maskPhone(Store.phone(this)) + " · 自动续期");
            tvAuth.setTextColor(getResources().getColor(R.color.muted));
            btnAuth.setText("重新登录");
        } else {
            tvAuth.setText("未登录（点右侧登录）");
            tvAuth.setTextColor(getResources().getColor(R.color.accent));
            btnAuth.setText("手机号登录");
        }

        long nx = Store.nextSlot(this);
        tvNext.setText(nx > 0
                ? "下次自动班次：" + Store.fmt(nx, "MM-dd HH:mm")
                : "下次自动班次：未排定（请检查自动开关）");
        JSONArray lg = Store.logs(this);
        StringBuilder lb = new StringBuilder();
        for (int i = 0; i < lg.length() && i < 3; i++) {
            JSONObject lo = lg.optJSONObject(i);
            if (lo == null) {
                continue;
            }
            if (lb.length() > 0) {
                lb.append("\n");
            }
            lb.append(Store.fmt(lo.optLong("ts"), "MM-dd HH:mm:ss")).append("  ").append(lo.optString("t"));
        }
        tvLogPreview.setText(lb.length() == 0 ? getString(R.string.log_empty) : lb.toString());

        JSONArray arr = Store.records(this);
        if (arr.length() == 0) {
            tvRecords.setText(R.string.records_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(Store.fmt(o.optLong("ts"), "MM-dd HH:mm")).append("  ")
              .append(o.optBoolean("ok") ? "✅ " : "⚠️ ")
              .append(o.optString("msg"));
        }
        tvRecords.setText(sb.toString());
    }

    // ---------- 运行日志 ----------
    private void showLogDialog() {
        JSONArray arr = Store.logs(this);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            sb.append(Store.fmt(o.optLong("ts"), "MM-dd HH:mm:ss")).append("  ")
              .append(o.optString("t")).append("\n");
        }
        if (sb.length() == 0) {
            sb.append(getString(R.string.log_empty));
        }
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setText(sb.toString());
        tv.setPadding(30, 20, 30, 20);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.log_label)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ---------- 登录（手机号+短信验证码，登录一次自动续期） ----------
    private void showLoginDialog() {
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        lay.setPadding((int) (18 * d), (int) (8 * d), (int) (18 * d), 0);

        final EditText etPhone = new EditText(this);
        etPhone.setHint("手机号（统一认证绑定的号码）");
        etPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        etPhone.setText(Store.phone(this));

        final Button btnSend = new Button(this);
        btnSend.setAllCaps(false);
        btnSend.setText("发送验证码");

        final EditText etCode = new EditText(this);
        etCode.setHint("短信验证码");
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);

        TextView tvHint = new TextView(this);
        tvHint.setTextSize(12);
        tvHint.setTextColor(getResources().getColor(R.color.muted));
        tvHint.setText("登录一次即可，之后 App 自动续期（无需再收短信）。\n如收不到短信，请确认该号码已绑定学校统一认证。");

        lay.addView(etPhone);
        lay.addView(btnSend);
        lay.addView(etCode);
        lay.addView(tvHint);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("手机号登录")
                .setView(lay)
                .setPositiveButton("登录", null)
                .setNegativeButton("取消", null)
                .create();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String phone = etPhone.getText().toString().trim();
                if (phone.length() < 11) {
                    Toast.makeText(MainActivity.this, "请输入 11 位手机号", Toast.LENGTH_SHORT).show();
                    return;
                }
                btnSend.setEnabled(false);
                btnSend.setText("发送中…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String err = null;
                        try {
                            Auth.loginSendSms(phone);
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final String fErr = err;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (fErr == null) {
                                    btnSend.setText("重新发送(60s)");
                                    Toast.makeText(MainActivity.this,
                                            "验证码已发送，请查收短信（可能有延迟）",
                                            Toast.LENGTH_LONG).show();
                                    btnSend.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            btnSend.setEnabled(true);
                                            btnSend.setText("重新发送");
                                        }
                                    }, 60000);
                                } else {
                                    btnSend.setEnabled(true);
                                    btnSend.setText("发送验证码");
                                    Store.addRecord(MainActivity.this, false, "发送失败：" + fErr);
                                    Store.addLog(MainActivity.this, "发送验证码失败：" + fErr);
                                    refreshUi();
                                    Toast.makeText(MainActivity.this,
                                            "发送失败：" + fErr, Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                final String phone = etPhone.getText().toString().trim();
                                final String code = etCode.getText().toString().trim();
                                if (phone.length() < 11 || code.isEmpty()) {
                                    Toast.makeText(MainActivity.this, "请填写手机号和验证码",
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                final Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                                ok.setEnabled(false);
                                ok.setText("登录中…");
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        String err = null;
                                        try {
                                            Auth.loginWithSms(MainActivity.this, phone, code);
                                        } catch (Exception e) {
                                            err = e.getMessage();
                                        }
                                        final String fErr = err;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (fErr == null) {
                                                    dlg.dismiss();
                                                    Store.addLog(MainActivity.this, "登录成功（" + maskPhone(phone) + "）");
                                                    refreshUi();
                                                    queryStatusAsync();
                                                    Toast.makeText(MainActivity.this,
                                                            "登录成功，已开启自动续期",
                                                            Toast.LENGTH_LONG).show();
                                                } else {
                                                    ok.setEnabled(true);
                                                    ok.setText("登录");
                                                    Store.addRecord(MainActivity.this, false,
                                                            "登录失败：" + fErr);
                                                    Store.addLog(MainActivity.this, "登录失败：" + fErr);
                                                    refreshUi();
                                                    new AlertDialog.Builder(MainActivity.this)
                                                            .setTitle("登录失败")
                                                            .setMessage(fErr)
                                                            .setPositiveButton("知道了", null)
                                                            .show();
                                                }
                                            }
                                        });
                                    }
                                }).start();
                            }
                        });
            }
        });
        dlg.show();
    }

    private String maskPhone(String p) {
        if (p == null || p.isEmpty()) {
            return "未知号码";
        }
        if (p.length() < 7) {
            return p;
        }
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    // ---------- 白名单 ----------
    private void applyBatteryWhitelist() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "已在电池优化白名单", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "请在系统设置中手动加入白名单", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }
}
