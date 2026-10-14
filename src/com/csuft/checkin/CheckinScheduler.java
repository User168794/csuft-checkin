package com.csuft.checkin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * 定时：打卡窗口（21:00–22:30）内多班次；幂等，已打卡的班次自动跳过。
 * v2.12：新增后台心跳（诊断链）；精确闹钟不可用时兜底升级为 setAlarmClock。
 */
final class CheckinScheduler {

    static final int WINDOW_START_MIN = 21 * 60;
    static final int WINDOW_END_MIN = 22 * 60 + 30;
    static final int[] SLOTS = {21 * 60 + 5, 21 * 60 + 35, 22 * 60 + 5, 22 * 60 + 25};
    static final int RECHECK_MIN = 22 * 60 + 35;
    private static final int RETRY_RC = 999;
    private static final int RECHECK_RC = 1000;
    private static final int HEARTBEAT_RC = 1001;
    static final long HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L;

    private CheckinScheduler() {
    }

    private static PendingIntent slotPi(Context c, int slot) {
        Intent i = new Intent(c, TriggerReceiver.class);
        i.setAction(TriggerReceiver.ACTION_RUN);
        i.putExtra("slot", slot);
        return PendingIntent.getBroadcast(c, slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent retryPi(Context c) {
        Intent i = new Intent(c, TriggerReceiver.class);
        i.setAction(TriggerReceiver.ACTION_RUN);
        i.putExtra("retry", true);
        return PendingIntent.getBroadcast(c, RETRY_RC, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent recheckPi(Context c) {
        Intent i = new Intent(c, TriggerReceiver.class);
        i.setAction(TriggerReceiver.ACTION_RUN);
        i.putExtra("recheck", true);
        return PendingIntent.getBroadcast(c, RECHECK_RC, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent heartbeatPi(Context c) {
        Intent i = new Intent(c, TriggerReceiver.class);
        i.setAction(TriggerReceiver.ACTION_RUN);
        i.putExtra("heartbeat", true);
        return PendingIntent.getBroadcast(c, HEARTBEAT_RC, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        if (!Store.auto(c)) {
            cancelAll(c);
            Store.setNextSlot(c, 0);
            return;
        }
        long next = Long.MAX_VALUE;
        for (int s : SLOTS) {
            long t = nextOccurrence(s);
            setExact(c, am, t, slotPi(c, s));
            if (t < next) {
                next = t;
            }
        }
        if (Store.recheck(c)) {
            long t = nextOccurrence(RECHECK_MIN);
            setExact(c, am, t, recheckPi(c));
            if (t < next) {
                next = t;
            }
        } else {
            am.cancel(recheckPi(c));
        }
        Store.setNextSlot(c, next == Long.MAX_VALUE ? 0 : next);
    }

    static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        for (int s : SLOTS) {
            am.cancel(slotPi(c, s));
        }
        am.cancel(retryPi(c));
        am.cancel(recheckPi(c));
        am.cancel(heartbeatPi(c));
    }

    static void retrySoon(Context c, int seconds) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        setExact(c, am, System.currentTimeMillis() + seconds * 1000L, retryPi(c));
    }

    /** 后台心跳（诊断）：退到后台起每 15 分钟唤醒记账，直到回到前台 / 被系统清理 / 关机 */
    static void armHeartbeat(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS, heartbeatPi(c));
        } catch (Exception ignored) {
        }
    }

    static void cancelHeartbeat(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.cancel(heartbeatPi(c));
    }

    static boolean inWindowNow() {
        Calendar n = Calendar.getInstance();
        int m = n.get(Calendar.HOUR_OF_DAY) * 60 + n.get(Calendar.MINUTE);
        return m >= WINDOW_START_MIN && m <= WINDOW_END_MIN;
    }

    private static long nextOccurrence(int slotMin) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, slotMin / 60);
        c.set(Calendar.MINUTE, slotMin % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private static void setExact(Context c, AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                setAlarmClock(c, am, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            setAlarmClock(c, am, at, pi);
        }
    }

    /** 兜底（最强档）：setAlarmClock —— 免 doze / 免多数厂商省电限制；状态栏可能出现闹钟小图标 */
    private static void setAlarmClock(Context c, AlarmManager am, long at, PendingIntent pi) {
        try {
            Intent show = new Intent(c, MainActivity.class);
            PendingIntent showPi = PendingIntent.getActivity(c, 1, show,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
        } catch (Exception e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Exception ignored) {
            }
        }
    }
}
