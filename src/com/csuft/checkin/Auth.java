package com.csuft.checkin;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录与自动续期（v2.8）：
 * - 续期：refresh_token → 换新令牌（滑动换发；每次打卡前自动执行，用户无感）
 * - 登录：学校统一认证（CAS）手机号 + 短信验证码 → 平台换票 → access + refresh
 * 全流程 1:1 复刻浏览器，参数取自 2026-10-09 抓包。
 * v2.8：平台换票跳转链存在服务端随机抖动（实测单发成功率约 1/3~1/2，与请求头/会话无关），
 *       改为带退避的自动重试：CAS 会话（TGC）在手，失败即重新出票重走链路，直至取得换票码。
 */
final class Auth {

    static final String CAS = "https://cas.csuft.edu.cn";
    static final String SERVICE = CheckinCore.BASE + "/api/flySource-cas/casLogin";

    /** 浏览器端表单附属字段（抓包原值）：fpVisitorId 为设备指纹（服务端仅记录使用） */
    private static final String FP_VISITOR_ID = "b15ed00ae1fe7a9ed6f67e4744ae4fef";

    /** 需要（重新）登录时抛出 */
    static final class NeedLogin extends Exception {
        NeedLogin(String msg) {
            super(msg);
        }
    }

    /** 登录两步之间保持的会话状态（同一进程内） */
    static final class Session {
        static String execution = "";
        static String cookies = "";
    }

    private Auth() {
    }

    // ---------- 令牌 ----------
    /** 返回可用的 access_token；必要时自动续期；需登录时抛 NeedLogin */
    static String getValidToken(Context c) throws Exception {
        String at = Store.accessToken(c);
        long exp = Store.accessExp(c);
        if (!at.isEmpty() && exp - System.currentTimeMillis() / 1000 > 300) {
            return at;
        }
        String rt = Store.refreshToken(c);
        if (rt.isEmpty()) {
            throw new NeedLogin("尚未登录，请先手机号登录");
        }
        return refresh(c, rt);
    }

    /** refresh_token → 新令牌（保存换发的新 refresh_token） */
    static String refresh(Context c, String rt) throws Exception {
        String q = "tenantId=000000&refresh_token=" + URLEncoder.encode(rt, "UTF-8")
                + "&grant_type=refresh_token&scope=all";
        JSONObject j = postJson(CheckinCore.BASE + "/api/flySource-auth/oauth/token?" + q);
        String at = j.optString("access_token", "");
        if (at.isEmpty()) {
            throw new NeedLogin("登录已失效，请重新登录");
        }
        String rt2 = j.optString("refresh_token", rt);
        Store.saveTokens(c, at, rt2, j.optLong("expires_in", 3599));
        return at;
    }

    // ---------- 手机号登录 ----------
    private static String loginPageUrl() throws Exception {
        return CAS + "/cas/login?service=" + URLEncoder.encode(SERVICE, "UTF-8");
    }

    /** 第一步：拉登录页（execution + 页面 cookie）→ 拉二维码组件（浏览器 JS 同款，会下发 SESSION）→ 发送短信 */
    static void loginSendSms(String phone) throws Exception {
        HttpReply page = get(loginPageUrl(), null);
        Matcher m = Pattern.compile("name=\"execution\" value=\"([^\"]+)\"").matcher(page.body);
        if (!m.find()) {
            throw new Exception("登录页解析失败（未找到 execution）");
        }
        Session.execution = m.group(1);
        String cookies = page.cookies;
        // 浏览器打开登录页后 JS 会请求二维码组件（此请求会建立 SESSION）——保持 1:1
        HttpReply qr = get(CAS + "/cas/qr/qrcode?r=" + System.currentTimeMillis(), cookies);
        cookies = mergeCookies(cookies, qr.cookies);

        HttpReply pr = postForm(CAS + "/cas/passwordlessTokenSend",
                "username=" + URLEncoder.encode(phone, "UTF-8") + "&captcha=",
                cookies, true);
        Session.cookies = mergeCookies(cookies, pr.cookies);
        String hint = hintText(pr.body);
        if (hint.contains("频繁") || hint.contains("失败") || hint.contains("错误")
                || hint.contains("上限") || hint.contains("稍后再试")) {
            throw new Exception("发送未成功：" + hint);
        }
    }

    /** 第二步：验证码登录 → 随跳转链取 tgc（失败自动重试）→ 换票并保存令牌 */
    static void loginWithSms(Context c, String phone, String code) throws Exception {
        if (Session.execution.isEmpty()) {
            throw new Exception("请先点击「发送验证码」");
        }
        String body = "username=" + URLEncoder.encode(phone, "UTF-8")
                + "&password=" + URLEncoder.encode(code, "UTF-8")
                + "&captcha=&currentMenu=2&failN=-1"
                + "&execution=" + URLEncoder.encode(Session.execution, "UTF-8")
                + "&_eventId=submitPasswordlessToken"
                + "&geolocation="
                + "&fpVisitorId=" + FP_VISITOR_ID
                + "&trustAgent="
                + "&submit2=Login2";
        String url = loginPageUrl();
        HttpReply r = postForm(url, body, Session.cookies, true);
        Session.execution = "";     // 登录门票一次性：提交后立即作废（防止复用出现 401）
        Session.cookies = "";
        if (r.status != 301 && r.status != 302 && r.status != 303) {
            String snip = strip(r.body);
            if (snip.length() > 70) {
                snip = snip.substring(0, 70);
            }
            throw new Exception("登录未通过（HTTP " + r.status + "）" + snip);
        }
        // CAS 会话票据（TGC）：换票跳转链抖动时用它重新出票重试
        String tgcSess = cookieValue(r.cookies, "TGC");
        if (tgcSess.isEmpty()) {
            throw new Exception("登录未通过（未取得 CAS 会话票据）");
        }
        String tgc = fetchTgc(r.location, tgcSess);
        String q = "tenantId=000000&tgc=" + URLEncoder.encode(tgc, "UTF-8")
                + "&grant_type=cas&scope=all";
        String ref = CheckinCore.BASE + "/login?tgc=" + URLEncoder.encode(tgc, "UTF-8");
        JSONObject j = postJson(CheckinCore.BASE + "/api/flySource-auth/oauth/token?" + q, null, ref);
        String at = j.optString("access_token", "");
        String rt = j.optString("refresh_token", "");
        if (at.isEmpty() || rt.isEmpty()) {
            throw new Exception("换票失败：" + j);
        }
        Store.saveTokens(c, at, rt, j.optLong("expires_in", 3599));
        Store.setPhone(c, phone);
    }

    /**
     * 走换票跳转链取 tgc。服务端偶发随机拒绝（实测与请求头/会话/连接完全无关——2026-10-10 凌晨
     * 浏览器抓包实锤：会话接力完美仍 7/7 全败；坏窗口可持续数十秒），失败即用 TGC 重新出票重试，
     * 退避间隔从 0.7s 渐增至 8s，共 19 次尝试（约 1 分钟）。
     */
    private static String fetchTgc(String firstTicket, String tgcSess) throws Exception {
        int[] sleeps = {700, 700, 1000, 1000, 1500, 1500, 2000, 2000, 3000, 3000,
                4000, 4000, 5000, 5000, 5000, 6000, 6000, 8000};
        String ticket = firstTicket == null ? "" : firstTicket;
        String tgc = "";
        String last = "";
        for (int i = 0; i <= sleeps.length && tgc.isEmpty(); i++) {
            if (ticket.isEmpty()) {
                // CAS 会话在手：走统一认证 SSO 静默出新票
                HttpReply mr = getRef(loginPageUrl(), "TGC=" + tgcSess, CAS + "/");
                if (mr.status == 302 && mr.location.contains("ticket=ST-")) {
                    ticket = mr.location;
                } else {
                    last = "（重出票 HTTP " + mr.status + "）";
                    break;
                }
            }
            HttpReply h1 = getRef(ticket, null, CAS + "/");
            Matcher m1 = Pattern.compile("tgc=([A-Za-z0-9\\-_]+)").matcher(h1.location);
            if (m1.find()) {
                tgc = m1.group(1);
                break;
            }
            String s1 = cookieValue(h1.cookies, "JSESSIONID");
            HttpReply h2 = getRef(CheckinCore.BASE + "/api/flySource-cas/casLogin",
                    s1.isEmpty() ? null : "JSESSIONID=" + s1, CAS + "/");
            Matcher m2 = Pattern.compile("tgc=([A-Za-z0-9\\-_]+)").matcher(h2.location);
            if (m2.find()) {
                tgc = m2.group(1);
                break;
            }
            last = "（第 " + (i + 1) + " 次跳转 HTTP " + h2.status + "）";
            ticket = "";            // 票据一次性：失败即作废，下一轮重新出票
            if (i < sleeps.length) {
                try {
                    Thread.sleep(sleeps[i]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Exception("登录已取消");
                }
            }
        }
        if (tgc.isEmpty()) {
            throw new Exception("登录换票未完成" + last + "（学校服务器繁忙），已自动重试近 1 分钟；"
                    + "请稍等 1~2 分钟后再次点击登录");
        }
        return tgc;
    }

    // ---------- HTTP 小件 ----------
    static final class HttpReply {
        int status;
        String body = "";
        String location = "";
        String cookies = "";
    }

    /** 合并 cookie（后者同名覆盖），全程累积，模拟浏览器的 CookieJar */
    static String mergeCookies(String old, String add) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String part : (old == null ? "" : old).split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        for (String part : (add == null ? "" : add).split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    /** 取指定 cookie 的值（不存在返回空串） */
    static String cookieValue(String cookies, String name) {
        if (cookies == null) {
            return "";
        }
        for (String part : cookies.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return "";
    }

    /** 去标签取文本（用于把服务器返回的提示原样带给用户） */
    static String strip(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /** 从 JSON 响应里取提示文本（如 passwordlessTokenSend 的 data.success） */
    static String hintText(String body) {
        try {
            JSONObject j = new JSONObject(body == null ? "{}" : body);
            JSONObject d = j.optJSONObject("data");
            if (d != null) {
                String s = d.optString("success", "");
                if (!s.isEmpty()) {
                    return s;
                }
                s = d.optString("msg", "");
                if (!s.isEmpty()) {
                    return s;
                }
            }
            String s = j.optString("msg", "");
            return s.isEmpty() ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static HttpReply send(String method, String url, String formBody,
                                  String cookies, boolean noRedirect) throws Exception {
        return send(method, url, formBody, cookies, noRedirect, null, false);
    }

    /** referer 非空时：补 Origin/Referer 与 JSON Accept（如平台换票请求，1:1 浏览器） */
    private static HttpReply send(String method, String url, String formBody,
                                  String cookies, boolean noRedirect,
                                  String referer) throws Exception {
        return send(method, url, formBody, cookies, noRedirect, referer, false);
    }

    /** 浏览器导航式 GET（换票跳转链 1:1：完整浏览器 Accept，按需带 Referer，不带 Origin） */
    private static HttpReply getRef(String url, String cookies, String referer) throws Exception {
        return send("GET", url, null, cookies, true, referer, true);
    }

    private static HttpReply send(String method, String url, String formBody,
                                  String cookies, boolean noRedirect,
                                  String referer, boolean nav) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(!noRedirect);
        conn.setRequestProperty("User-Agent", Secrets.UA);
        if (nav) {
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,"
                            + "image/avif,image/webp,image/apng,*/*;q=0.8,"
                            + "application/signed-exchange;v=b3;q=0.7");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
        } else {
            conn.setRequestProperty("Accept", "text/html,application/json,*/*");
            if (url.startsWith(CheckinCore.BASE + "/api/flySource-auth/")) {
                // oauth 系列接口需要 OAuth2 客户端认证（Basic）与租户头（缺一报“系统未知异常[HttpStatus]:401”）
                conn.setRequestProperty("Authorization", Secrets.BASIC);
                conn.setRequestProperty("Tenant-Id", "000000");
            }
            if ("POST".equals(method) && url.startsWith(CAS)) {
                conn.setRequestProperty("Origin", CAS);
                conn.setRequestProperty("Referer", loginPageUrl());
            } else if (referer != null && !referer.isEmpty()) {
                String origin = referer;
                int cut = referer.indexOf("/", referer.indexOf("://") + 3);
                if (cut > 0) {
                    origin = referer.substring(0, cut);
                }
                conn.setRequestProperty("Origin", origin);
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            }
        }
        if (cookies != null && !cookies.isEmpty()) {
            conn.setRequestProperty("Cookie", cookies);
        }
        if (formBody != null) {
            conn.setDoOutput(true);
            if (!formBody.isEmpty()) {
                conn.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
            }
            byte[] out = formBody.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(out.length);
            OutputStream os = conn.getOutputStream();
            os.write(out);
            os.close();
        } else if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            conn.getOutputStream().close();
        }
        HttpReply rep = new HttpReply();
        rep.status = conn.getResponseCode();
        String loc = conn.getHeaderField("Location");
        rep.location = loc == null ? "" : loc;
        StringBuilder ck = new StringBuilder();
        for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String sc : e.getValue()) {
                    if (sc == null) {
                        continue;
                    }
                    String pair = sc.split(";", 2)[0];
                    if (pair.contains("=")) {
                        if (ck.length() > 0) {
                            ck.append("; ");
                        }
                        ck.append(pair);
                    }
                }
            }
        }
        rep.cookies = ck.toString();
        InputStream is = (rep.status >= 400) ? conn.getErrorStream() : conn.getInputStream();
        rep.body = readAll(is);
        conn.disconnect();
        return rep;
    }

    private static HttpReply get(String url, String cookies) throws Exception {
        return send("GET", url, null, cookies, true);
    }

    private static HttpReply postForm(String url, String formBody, String cookies,
                                      boolean noRedirect) throws Exception {
        return send("POST", url, formBody, cookies, noRedirect);
    }

    /** POST 空体并解析 JSON（oauth/token 系列） */
    private static JSONObject postJson(String url) throws Exception {
        return postJson(url, null, null);
    }

    /** POST 空体并解析 JSON；可带 cookie 与 Referer（换票请求 1:1 浏览器） */
    private static JSONObject postJson(String url, String cookies, String referer) throws Exception {
        HttpReply r = send("POST", url, "", cookies, false, referer);
        String body = r.body == null ? "" : r.body.trim();
        if (body.startsWith("<")) {
            throw new Exception("接口返回网页而非数据（HTTP " + r.status + "）");
        }
        return new JSONObject(body);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
