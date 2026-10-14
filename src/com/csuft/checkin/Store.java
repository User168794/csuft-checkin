package com.csuft.checkin;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 本地存储：令牌 / 自动开关 / 最近结果与记录 / 重试计数 */
final class Store {

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("checkin", Context.MODE_PRIVATE);
    }

    static String accessToken(Context c) {
        return sp(c).getString("access", "");
    }

    static long accessExp(Context c) {
        return sp(c).getLong("access_exp", 0);
    }

    static String refreshToken(Context c) {
        return sp(c).getString("refresh", "");
    }

    static String phone(Context c) {
        return sp(c).getString("phone", "");
    }

    static void setPhone(Context c, String p) {
        sp(c).edit().putString("phone", p).apply();
    }

    static boolean isLoggedIn(Context c) {
        return !refreshToken(c).isEmpty();
    }

    /** 保存令牌（expires_in 秒；滑动换发时新 refresh_token 一并保存） */
    static void saveTokens(Context c, String access, String refresh, long expiresIn) {
        sp(c).edit()
                .putString("access", access)
                .putString("refresh", refresh)
                .putLong("access_exp", System.currentTimeMillis() / 1000 + Math.max(60, expiresIn))
                .apply();
    }

    static void clearAuth(Context c) {
        sp(c).edit().remove("access").remove("refresh").remove("access_exp").apply();
    }

    static boolean auto(Context c) {
        return sp(c).getBoolean("auto", true);
    }

    static void setAuto(Context c, boolean b) {
        sp(c).edit().putBoolean("auto", b).apply();
    }

    static boolean recheck(Context c) {
        return sp(c).getBoolean("recheck", true);
    }

    static void setRecheck(Context c, boolean b) {
        sp(c).edit().putBoolean("recheck", b).apply();
    }

    static void setLast(Context c, boolean ok, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("msg", msg);
            o.put("ts", System.currentTimeMillis());
            sp(c).edit().putString("last", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void addRecord(Context c, boolean ok, String msg) {
        try {
            JSONArray arr = new JSONArray(sp(c).getString("records", "[]"));
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("msg", msg);
            o.put("ts", System.currentTimeMillis());
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && i < 13; i++) {
                out.put(arr.get(i));
            }
            sp(c).edit().putString("records", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static JSONArray records(Context c) {
        try {
            return new JSONArray(sp(c).getString("records", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // ---------- 运行日志（诊断：生命周期/心跳/班次） ----------
    /** 追加一条运行日志（最新在前，保留最近 300 条） */
    static void addLog(Context c, String text) {
        try {
            JSONArray arr = new JSONArray(sp(c).getString("runlog", "[]"));
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("t", text);
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && i < 299; i++) {
                out.put(arr.get(i));
            }
            sp(c).edit().putString("runlog", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static JSONArray logs(Context c) {
        try {
            return new JSONArray(sp(c).getString("runlog", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static long lastAttempt(Context c) {
        return sp(c).getLong("last_attempt", 0);
    }

    static void setLastAttempt(Context c, long ms) {
        sp(c).edit().putLong("last_attempt", ms).apply();
    }

    static long nextSlot(Context c) {
        return sp(c).getLong("next_slot", 0);
    }

    static void setNextSlot(Context c, long ms) {
        sp(c).edit().putLong("next_slot", ms).apply();
    }

    static String fmt(long ts, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(ts));
    }

    /** 后台重试链计数（每日重置，最多 3 次） */
    static int incRetry(Context c) {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String d = sp(c).getString("retry_day", "");
        int n = sp(c).getInt("retry_n", 0);
        if (!day.equals(d)) {
            n = 0;
            d = day;
        }
        n++;
        sp(c).edit().putString("retry_day", d).putInt("retry_n", n).apply();
        return n;
    }
}
