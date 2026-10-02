# Weverse DM Translator

<p align="center">
  <a href="README.md"><b>简体中文</b></a> · <a href="README.en.md">English</a>
</p>

一个面向 Android 的 Weverse DM 实时韩中翻译工具。它通过 Android `AccessibilityService` 读取 Weverse 当前可见的韩文消息，使用 ChatGPT 在线翻译，并将简体中文直接覆盖到对应的艺人消息气泡区域。

> 非官方项目。本项目与 Weverse、HYBE、OpenAI 均无隶属、赞助或官方合作关系。

## 直接下载

### [⬇ 下载 WeverseTranslator v0.5.3 APK](https://github.com/Tracywu1/weverse-translator/releases/download/v0.5.3/WeverseTranslator-v0.5.3.apk)

也可以前往 [GitHub Releases](https://github.com/Tracywu1/weverse-translator/releases) 查看版本记录。

当前版本：**v0.5.3**

## 主要功能

- Weverse DM 可见韩文实时翻译为简体中文
- 使用 `Continue with ChatGPT` 登录符合条件的 ChatGPT Plus / Pro 账号
- App 内无需填写 API Key
- 翻译模型优先选择 **Sol**，不可用时自动回退
- 只使用**艺人侧连续韩文消息**作为翻译上下文，不读取右侧用户中文回复作为上下文
- 人名、昵称识别采用更保守策略，降低把普通词误判成人名的概率
- 对短句、拟声词、造词、双关采取保守翻译，无法可靠判断时优先保留原词或轻量音译
- 翻译请求进行时，新消息会继续进入待翻译队列
- 较长且稳定的成功译文会缓存到本机，减少重复请求
- 短句不跨重启持久缓存，降低不同语境下复用旧译文的问题
- 自动过滤发送者昵称及部分非消息韩文 UI 文本
- 中文直接覆盖在原艺人消息区域，不使用底部字幕面板
- Overlay 会跟随原消息位置更新
- 首页提供 ChatGPT、无障碍服务、Weverse 检测和实时翻译状态
- 翻译请求使用 `store: false`
- 纯文本无障碍读取，不截屏，不使用 OCR

## 使用方法

1. 下载并安装上面的 APK。
2. 打开 **Weverse DM Translator**。
3. 点击 **Continue with ChatGPT**，完成 ChatGPT 套餐授权。
4. 点击「打开无障碍设置」。
5. 开启 **Weverse DM 翻译** 无障碍服务。
6. 返回 App，确认 ChatGPT、无障碍服务和 Weverse 状态正常。
7. 打开 Weverse，进入 DM 页面。
8. 当前可见的韩文消息会自动翻译并显示中文。

如果想暂时停止翻译，可回到 App 首页关闭「实时翻译」。

> Android 在卸载 App 后会清除无障碍授权和本机登录数据。如果先卸载旧版再安装新版，需要重新进行 ChatGPT 授权和无障碍授权。

## 工作原理

```text
Weverse DM 页面
      ↓
Android AccessibilityService
      ↓
读取可见韩文 + 屏幕位置
      ↓
过滤发送者昵称 / UI 文本
      ↓
新消息队列 + 最近艺人侧韩文上下文
      ↓
ChatGPT OAuth + Responses API
      ↓
自然中文翻译
      ↓
本地翻译缓存
      ↓
将中文 Overlay 对齐到原消息区域
```

无障碍服务仅限定监听 Weverse Android 包名：

```text
co.benx.weverse
```

## 翻译策略

当前翻译逻辑针对偶像私信场景进行了专门调整：

- 先把艺人连续发出的韩文当作一段上下文理解，再逐条输出译文
- 不把右侧用户中文回复作为上下文
- 根据艺人侧上下文还原韩语里常省略的主语、指代和语气
- 优先保证准确，再追求自然中文
- 人名判断采用保守策略，缺少明确证据时优先按普通词和句法理解
- 粉圈造词、拟声词、临时梗含义不确定时避免强行解释
- 保留猫 / 狗等角色梗、聊天语气和称呼
- 对错别字、连写、口语缩写、造词采取保守解释
- 保留 `ㅋㅋ`、`ㅎㅎ`、`ㅠㅠ`、emoji、撒娇感和调侃语气
- 避免自行增加原文中没有的人名、人物关系、暧昧程度或事实

## 隐私说明

- 无障碍服务只监听 Weverse 包名
- 只有用于翻译的艺人侧韩文和最近艺人侧韩文上下文会发送到 OpenAI
- 翻译请求使用 `store: false`
- ChatGPT OAuth 凭据保存在 App 私有存储中
- 成功译文缓存在本机
- App 不截取屏幕
- App 不使用 OCR
- 本项目没有自建云端数据库或中转翻译服务器

Android 无障碍权限能力较强。安装来自第三方的 APK 前，建议先检查对应源码和发布来源。

## 当前限制

- 消息提取依赖 Weverse 当前页面向 Android 暴露的无障碍文本节点
- 如果某条消息本身没有可访问文本，本版本会跳过该条消息
- 很长的中文译文可能需要自动缩小字号以适配原消息区域
- 极短且高度依赖隐含对话的句子仍可能存在歧义
- 当前仅针对韩文 → 简体中文进行优化
- 开发阶段 APK 使用 Debug 签名，某些情况下更新安装可能需要先卸载旧版

## 项目结构

```text
.
├── app/
│   └── src/main/
│       ├── java/com/cc/weversetranslator/
│       │   ├── AppPrefs.kt
│       │   ├── MainActivity.kt
│       │   ├── OpenAiAuth.kt
│       │   ├── OverlayController.kt
│       │   ├── TranslationCache.kt
│       │   ├── TranslationClient.kt
│       │   └── WeverseAccessibilityService.kt
│       └── res/
├── .github/workflows/
└── README.*
```

## 后续计划

- Release 正式签名
- 更多目标语言

## 版本演进

1. 底部翻译面板
2. 独立白色翻译卡片
3. 自适应长文本卡片
4. v0.4.0：气泡区域内嵌 Overlay
5. v0.5.0：上下文翻译、持久缓存和交互优化
6. v0.5.1：移除 OCR 和截屏链路
7. v0.5.2：移除 Weverse 内「译 / 原」和单气泡切换
8. v0.5.3：上下文限定为艺人侧韩文，人名判断更保守，并使旧错误缓存失效

## 免责声明

Weverse DM 内容可能受 Weverse 自身条款和内容使用规则约束。用户需自行负责对 DM 内容的访问、处理、存储和分享方式。本仓库定位为个人翻译辅助工具和开发项目。
