package com.csuft.checkin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 打卡结果通知 + 运行状态通知（v2.12：App 关闭提醒） */
final class Notify {

    private static final String CH = "checkin";
    private static final String CH_STATE = "state";

    private Notify() {
    }

    static void ensure(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "打卡结果",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private static void ensureState(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH_STATE) == null) {
                NotificationChannel ch = new NotificationChannel(CH_STATE, "运行状态",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
        }
    }

    static void done(Context c, String msg) {
        try {
            ensure(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent i = new Intent(c, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.checkbox_on_background)
                    .setContentTitle("平安打卡")
                    .setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(1, b.build());
        } catch (Exception ignored) {
        }
    }

    /** App 关闭（用户主动退出/划掉任务）时发状态栏提醒：自动打卡需要 App 未被系统清理 */
    static void closed(Context c) {
        try {
            ensureState(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent i = new Intent(c, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String msg = Store.fmt(System.currentTimeMillis(), "HH:mm")
                    + " 已退出界面。到点自动打卡需要 App 未被系统清理，建议重新打开并锁定后台（详见运行日志）。";
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(c, CH_STATE) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("平安打卡已关闭")
                    .setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(2, b.build());
        } catch (Exception ignored) {
        }
    }
}
