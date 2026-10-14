package com.csuft.checkin;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 平安打卡核心逻辑：与 The Spider 技能 1:1 移植（纯 HTTP + 实时签名） */
public final class CheckinCore {

    public static final String BASE = "https://simp.csuft.edu.cn";
    public static final String TASK_ID = "45f90f7a3a61ccd4e35611bb59ed7f25";
    public static final String REC_PATH = "/api/flySource-yxgl/dormSignRecord/getOne";
    public static final String TASK_PATH = "/api/flySource-yxgl/dormSignTask/getTaskByIdForApp";
    public static final String SIGN_PATH = "/api/flySource-yxgl/dormSignRecord/stuSign";
    /**
     * 打卡坐标（v2.11）：两级随机——
     * ① 以【宿舍登记点】（任务详情 dormitoryRegisterVO，即小程序"距离XX米"读数为 0 的那个点）
     *    为圆心、300m 圆内均匀随机 → P1；
     * ② 以 P1 为圆心、500m 圆内再均匀随机 → P2，最终提交 P2。
     * 距登记点最远 800m；服务端允许范围 = 任务详情 locationAccuracy（实测 2000 米），安全。
     */
    public static final double ORIGIN_LAT = 28.1310823200672;
    public static final double ORIGIN_LNG = 112.994627660259;
    public static final double R1_M = 300.0;
    public static final double R2_M = 500.0;
    private static final double M_PER_DEG_LAT = 111320.0;

    public interface Log {
        void line(String action, String result);
    }

    public static final class Res {
        public final boolean ok;
        public final String msg;
        public String signTime = "";
        public double dist = -1;
        public int attempts = 0;

        public Res(boolean ok, String msg) {
            this.ok = ok;
            this.msg = msg;
        }
    }

    private CheckinCore() {
    }

    // ---------- 基础 ----------
    static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** FlySource-sign = md5(path + "?sign=" + md5(毫秒时间戳 + token)) + "1." + base64(时间戳) */
    static String sign(String path, String token) throws Exception {
        long n = System.currentTimeMillis();
        String inner = md5(n + token);
        String outer = md5(path + "?sign=" + inner);
        return outer + "1." + Base64.encodeToString(String.valueOf(n).getBytes("UTF-8"), Base64.NO_WRAP);
    }

    /** 校方同款 haversine（米，1 位小数） */
    static double distance(double lat1, double lng1, double lat2, double lng2) {
        double a = lat1 * Math.PI / 180;
        double i = lat2 * Math.PI / 180;
        double g = a - i;
        double o = lng1 * Math.PI / 180 - lng2 * Math.PI / 180;
        double l = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin(g / 2), 2)
                + Math.cos(a) * Math.cos(i) * Math.pow(Math.sin(o / 2), 2)));
        l *= 6378.137;
        return Math.round(l * 1e4) / 10.0;
    }

    static JSONObject http(String method, String path, String query, JSONObject body,
                           String token) throws Exception {
        URL url = new URL(BASE + path + (query == null ? "" : "?" + query));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", Secrets.UA);
        c.setRequestProperty("FlySource-Auth", token);
        c.setRequestProperty("Authorization", Secrets.BASIC);
        c.setRequestProperty("FlySource-sign", sign(path, token));
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] out = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(out.length);
            OutputStream os = c.getOutputStream();
            os.write(out);
            os.close();
        }
        int code = c.getResponseCode();
        if (code == 401 || code == 403) {
            throw new Exception("令牌失效（HTTP " + code + "），请更新令牌");
        }
        InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String text = readAll(is);
        c.disconnect();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("<")) {
            throw new Exception("登录已失效：接口返回网页（请更新令牌）");
        }
        return new JSONObject(trimmed);
    }

    static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    static JSONObject optObj(JSONObject o, String key) {
        JSONObject v = (o == null) ? null : o.optJSONObject(key);
        return v == null ? new JSONObject() : v;
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static String trimNum(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** 两级随机坐标（每级圆内面积均匀：r = R·√U、θ = 2πV；取 6 位小数，米→度按纬度校正） */
    static double[] randCoord() {
        double[] p1 = randInDisk(ORIGIN_LAT, ORIGIN_LNG, R1_M);
        return randInDisk(p1[0], p1[1], R2_M);
    }

    static double[] randInDisk(double lat, double lng, double radiusM) {
        double r = radiusM * Math.sqrt(Math.random());
        double th = 2 * Math.PI * Math.random();
        double mLng = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
        return new double[] {
                round6(lat + r * Math.cos(th) / M_PER_DEG_LAT),
                round6(lng + r * Math.sin(th) / mLng)};
    }

    static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    // ---------- 业务 ----------
    static String alreadySigned(String token, String day) throws Exception {
        JSONObject j = http("GET", REC_PATH, "taskId=" + TASK_ID, null, token);
        JSONObject data = optObj(j, "data");
        String d = data.optString("signDate", "");
        String t = data.optString("signTime", "");
        return (day.equals(d) && !t.isEmpty()) ? t : null;
    }

    /** 只查询今日状态（不提交，供打开应用时刷新界面） */
    public static Res queryStatus(String token) {
        try {
            String t = alreadySigned(token, today());
            if (t != null) {
                Res r = new Res(true, "今日已打卡：" + t);
                r.signTime = t;
                return r;
            }
            return new Res(false, "今日未打卡");
        } catch (Exception e) {
            return new Res(false, "查询失败：" + e.getMessage());
        }
    }

    /**
     * 完整打卡：查重 → 任务详情 → 提交 → 3 秒验证（失败隔 15 秒重试）。
     * fullRetry=true（前台手动）：最多 4 次提交；false（后台闹钟）：单次提交，重试交给闹钟链。
     */
    public static Res runOnce(String token, boolean fullRetry, Log log) {
        try {
            String day = today();
            String t0 = alreadySigned(token, day);
            if (t0 != null) {
                if (log != null) {
                    log.line("打卡检查", "今日已打卡（" + t0 + "），无需重复");
                }
                Res r = new Res(true, "今日已打卡：" + t0);
                r.signTime = t0;
                return r;
            }

            JSONObject j1 = http("GET", TASK_PATH, "taskId=" + TASK_ID, null, token);
            JSONObject d1 = optObj(j1, "data");
            JSONObject dv = optObj(d1, "dormitoryRegisterVO");
            String roomId = dv.optString("roomId", "");
            double allow = d1.optDouble("locationAccuracy", 2000);
            if (roomId.isEmpty()) {
                return new Res(false, "任务详情缺少宿舍信息（roomId）");
            }
            double[] rc = randCoord();          // v2.11：每次提交前生成独立两级随机点
            String latStr = String.valueOf(rc[0]);
            String lngStr = String.valueOf(rc[1]);
            double dist = distance(rc[0], rc[1],
                    dv.optDouble("locationLat", 0), dv.optDouble("locationLng", 0));
            if (dist >= allow) {
                return new Res(false, String.format(Locale.US,
                        "超出打卡范围：%.1f 米 > %s 米", dist, trimNum(allow)));
            }

            String accStr = Double.toString(dist);
            String canonical = "{\"latitude\":\"" + latStr + "\",\"longitude\":\"" + lngStr
                    + "\",\"locationAccuracy\":\"" + accStr + "\",\"signDate\":\"" + day
                    + "\",\"taskId\":\"" + TASK_ID + "\",\"fileId\":\"\"}";
            String stuMd5 = md5(canonical);

            JSONObject body = new JSONObject();
            body.put("taskId", TASK_ID);
            Object st = d1.opt("scanType");
            body.put("scanType", (st == null || String.valueOf(st).isEmpty()) ? "1" : String.valueOf(st));
            body.put("roomId", roomId);
            body.put("isLateStuTakePhoto", d1.optInt("isLateStuTakePhoto", 0));
            body.put("signType", 0);
            body.put("scanCode", "");
            body.put("fileId", "");
            body.put("signLat", rc[0]);
            body.put("signLng", rc[1]);
            body.put("locationAccuracy", dist);
            body.put("stuTaskId", stuMd5);

            int maxTry = fullRetry ? 4 : 1;
            String err = "";
            for (int attempt = 0; attempt < maxTry; attempt++) {
                JSONObject d2 = http("POST", SIGN_PATH, null, body, token);
                if (d2.optInt("code", -1) != 200 || !d2.optBoolean("success", false)) {
                    err = "接口返回：" + d2.optString("msg", "未知原因");
                } else {
                    if (log != null) {
                        log.line("提交打卡", "第 " + (attempt + 1) + " 次提交已受理（坐标 "
                                + latStr + "," + lngStr + " · 距宿舍 "
                                + String.format(Locale.US, "%.1f", dist) + " 米）");
                    }
                    sleep(3000);
                    String t = alreadySigned(token, day);
                    if (t != null) {
                        Res r = new Res(true, "平安打卡完成（" + day + " " + t + "）");
                        r.signTime = t;
                        r.dist = dist;
                        r.attempts = attempt + 1;
                        return r;
                    }
                    err = "提交后验证未生效";
                }
                if (attempt < maxTry - 1) {
                    sleep(15000);
                }
            }
            return new Res(false, "连续 " + maxTry + " 次打卡未成功：" + err);
        } catch (Exception e) {
            return new Res(false, "执行异常：" + e.getMessage());
        }
    }
}
