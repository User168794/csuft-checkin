package com.csuft.checkin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 闹钟班次 / 开机完成 / 窗口后复核 / 后台心跳（v2.12 诊断）：
 * - 打卡班次：先确保令牌有效（refresh 无感续期）→ 查→提交→3 秒验证；失败进重试链（≤3）。
 * - 复核班次（22:35，可在设置中关闭）：只查询、不提交，永远记录并通知最终判词。
 * - 心跳（15 分钟一次）：活着证明 + 窗口内顺带幂等打卡（距上次执行≥90 秒才动手）。
 * - 全程写运行日志：班次/心跳/静默跳过/结果 → 被系统清理时日志戛然而止，可定位时间点。
 */
public class TriggerReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.csuft.checkin.ACTION_RUN";

    private static final String NEED_LOGIN_MSG =
            "登录已失效，请打开「平安打卡」重新登录（手机号+验证码）";

    @Override
    public void onReceive(final Context ctx, Intent it) {
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(it.getAction());
        final boolean recheck = it.getBooleanExtra("recheck", false);
        final boolean heartbeat = it.getBooleanExtra("heartbeat", false);
        final boolean retry = it.getBooleanExtra("retry", false);

        if (heartbeat) {
            Store.addLog(ctx, "心跳（后台存活）");
            CheckinScheduler.armHeartbeat(ctx);
            if (Store.auto(ctx) && Store.isLoggedIn(ctx) && CheckinScheduler.inWindowNow()) {
                if (System.currentTimeMillis() - Store.lastAttempt(ctx) >= 90000) {
                    runAsync(ctx, false);
                } else {
                    Store.addLog(ctx, "心跳：距上次执行不足 90 秒，跳过打卡");
                }
            }
            return;
        }

        CheckinScheduler.scheduleAll(ctx);
        if (boot) {
            Store.addLog(ctx, "开机完成广播到达（闹钟已重挂）");
        }
        String label = recheck ? "复核班次" : (retry ? "重试班次"
                : (it.hasExtra("slot") ? "班次 " + fmtSlot(it.getIntExtra("slot", 0)) : "班次"));
        Store.addLog(ctx, label + " 触发");

        if (!Store.auto(ctx)) {
            Store.addLog(ctx, label + "：自动开关关闭，跳过");
            return;
        }
        if (!Store.isLoggedIn(ctx)) {
            Store.addLog(ctx, label + "：尚未登录，跳过");
            return;
        }
        if (recheck && !Store.recheck(ctx)) {
            return;
        }
        if (boot) {
            if (recheck || !CheckinScheduler.inWindowNow()) {
                return;
            }
        }
        runAsync(ctx, recheck);
    }

    private void runAsync(final Context ctx, final boolean recheck) {
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (recheck) {
                        doRecheck(ctx);
                    } else {
                        doCheckin(ctx);
                    }
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    private static String fmtSlot(int m) {
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private void doCheckin(Context ctx) {
        Store.setLastAttempt(ctx, System.currentTimeMillis());
        Store.addLog(ctx, "自动打卡：执行中…");
        CheckinCore.Res res;
        try {
            String token = Auth.getValidToken(ctx);
            res = CheckinCore.runOnce(token, false, null);
        } catch (Auth.NeedLogin e) {
            Store.setLast(ctx, false, NEED_LOGIN_MSG);
            Store.addRecord(ctx, false, NEED_LOGIN_MSG);
            Store.addLog(ctx, "自动打卡：登录失效");
            Notify.done(ctx, NEED_LOGIN_MSG);
            return;
        } catch (Exception e) {
            res = new CheckinCore.Res(false, "执行异常：" + e.getMessage());
        }
        boolean notable = !res.ok || res.attempts > 0;
        if (notable) {
            Store.setLast(ctx, res.ok, res.msg);
            Store.addRecord(ctx, res.ok, res.msg);
            Notify.done(ctx, res.msg);
        }
        Store.addLog(ctx, "自动打卡：" + res.msg);
        if (!res.ok && CheckinScheduler.inWindowNow()) {
            int n = Store.incRetry(ctx);
            if (n <= 3) {
                CheckinScheduler.retrySoon(ctx, 25);
            }
        }
    }

    /** 22:35 窗口结束后复核：只查询，永远记录并通知最终判词 */
    private void doRecheck(Context ctx) {
        CheckinCore.Res r;
        try {
            String token = Auth.getValidToken(ctx);
            r = CheckinCore.queryStatus(token);
        } catch (Auth.NeedLogin e) {
            Store.setLast(ctx, false, NEED_LOGIN_MSG);
            Store.addRecord(ctx, false, NEED_LOGIN_MSG);
            Store.addLog(ctx, "复核：登录失效");
            Notify.done(ctx, NEED_LOGIN_MSG);
            return;
        } catch (Exception e) {
            r = new CheckinCore.Res(false, "查询失败：" + e.getMessage());
        }
        boolean queryError = !r.ok && r.msg.startsWith("查询失败");
        String msg;
        if (r.ok) {
            msg = "复核通过：今日已打卡" + (r.signTime.isEmpty() ? "" : "（" + r.signTime + "）");
        } else if (queryError) {
            msg = "复核失败：" + r.msg.substring("查询失败：".length());
        } else {
            msg = "复核：今日未打卡（窗口 21:00–22:30 已结束）";
        }
        Store.setLast(ctx, r.ok, msg);
        Store.addRecord(ctx, r.ok, msg);
        Store.addLog(ctx, "复核：" + msg);
        Notify.done(ctx, msg);
    }
}
