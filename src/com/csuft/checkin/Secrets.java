package com.csuft.checkin;

/**
 * 公共常量（v2.0 起 APK 不再内置任何个人凭据）：
 * - BASIC：网页客户端级公共凭据（与小程序客户端不同源，但服务器不校验配对）
 * - UA：必须是完整浏览器串——服务端 WAF 会拒绝不完整 UA（实测 400）
 */
final class Secrets {

    static final String BASIC =
            "Basic Zmx5U291cmNlOkZseVNvdXJjZV9TREVLT0ZTSURGODIzMjlGOHNkODcyM2RTODdEQVM=";

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36";

    private Secrets() {
    }
}
