# 平安打卡 · csuft-checkin

> 中南林业科技大学学工部「平安打卡」的 Android 自动化客户端 —— 手机号短信登录一次，之后全自动续期与打卡；每日定时窗口自动执行 + 结果复核通知；纯 HTTP 实现，运行时无需微信/小程序。

个人自用工具，顺手开源。**使用须知与免责声明见文末。**

## ✨ 功能特性

- 🔐 **手机号短信登录**（学校 CAS 统一认证）：只需一次验证码登录；之后 `refresh_token` 滑动续期，打卡前自动完成，用户无感
- ⏰ **每日自动打卡**：21:05 / 21:35 / 22:05 / 22:25 四个班次自动尝试（受系统精确闹钟调度），已打卡自动跳过、静默不打扰
- ✅ **窗口结束复核**：22:35 自动查询并通知最终判词（是否打卡成功）
- 🎯 **坐标随机化**：提交坐标 = 以宿舍登记点为原点的两级随机（300 m → 500 m），每次提交都不同
- 📋 **运行日志（诊断）+ 后台心跳**：打开/关闭/退后台/心跳/班次触发/静默跳过原因/结果全量记录，界面内可查看
- 🔕 **状态栏通知**：打卡结果、登录失效提醒、主动退出提示
- ⚡ **免杀引导**：一键申请电池优化白名单 + 图文本地化引导（自启动 / 后台高耗电 / 最近任务锁定）

## ⚙️ 工作原理（简版）

1. **登录**：CAS 短信登录（`cas.csuft.edu.cn`）→ 拿 TGC → 换票（`ST`）→ OAuth2 换发 `access_token` / `refresh_token`。换票链路服务端存在随机抖动，客户端带自动重试与退避
2. **打卡**：三个 HTTP 接口 —— 查询今日记录 / 拉取任务详情 / 提交打卡；每个请求带实时签名 `FlySource-sign = md5(path + "?sign=" + md5(毫秒时间戳 + token)) + "1." + base64(时间戳)`
3. **坐标**：每次提交现算两级随机坐标；服务端允许范围为任务详情中的 `locationAccuracy`
4. **调度**：`AlarmManager` 精确闹钟 + 开机重挂 + 后台心跳兜底；每天只允许打卡一次

## 📱 使用说明

1. 安装 APK（构建产物不随仓库分发——自行构建，或向分享者索取）
2. 打开 App → 「手机号登录」→ 输入手机号、获取并填写短信验证码（仅此一次）
3. 保持「每日自动打卡」「窗口结束后复核」两个开关开启
4. 按界面引导完成免杀设置（电池优化白名单 / 自启动 / 后台高耗电 / 最近任务锁定），确保到点能唤醒

## 🔨 从源码构建

依赖：`bash` + JDK（javac via d8 运行需 java）+ Android SDK（`build-tools 36.1.0`、`platforms/android-36.1`）

```bash
# 如机器路径与脚本默认不同，用环境变量覆盖：
export ANDROID_SDK="C:/Users/ABC/AppData/Local/Android/Sdk"
export JAVA_BIN="/d/JAVA/bin"          # 含 javac 的目录
# export CHECKIN_OUT_DIR="/path/to/output"   # 产物输出目录（默认=仓库上一级）

bash build.sh
# 产物：平安打卡-v<版本>.apk（首次构建会自动生成 debug.keystore）
```

流水线纯手工：`aapt2 compile/link → javac → d8 → zip 注入 classes.dex → zipalign → apksigner`，无需 gradle。

## 📂 目录结构

```
CheckinApp/
├── AndroidManifest.xml          # minSdk 24 / targetSdk 36
├── build.sh                     # 构建流水线（见上）
├── gen_icons.py                 # 启动图标生成脚本
├── res/                         # 布局 / 字符串 / 图标 / 主题
└── src/com/csuft/checkin/
    ├── MainActivity.java        # 主界面：状态卡 / 登录 / 开关 / 日志 / 白名单
    ├── Auth.java                # CAS 短信登录 + 换票 + OAuth 续期
    ├── CheckinCore.java         # 打卡核心：三接口 + 实时签名 + 两级随机坐标
    ├── CheckinScheduler.java    # 精确闹钟班次调度 + 心跳
    ├── TriggerReceiver.java     # 闹钟/开机广播接收
    ├── Notify.java              # 通知
    ├── Store.java               # 本地状态与运行日志
    └── Secrets.java             # 客户端级公共常量（无个人凭据）
```

## ⚠️ 使用须知

- 本项目为个人自动化学习交流用途；打卡数据以学校系统为准
- 仓库中不含任何个人凭据（账号 / 令牌 / 学号均不落库、只存于手机本地）
- 任务 ID、登记点坐标等为学校侧的公共配置常量
- 请遵守学校相关规定，使用风险自负

## 📄 License

未声明许可证（保留所有权利）。如需他人自由使用/修改，可自行补充 MIT 等许可证。
