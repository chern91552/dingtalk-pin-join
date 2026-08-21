# 钉钉置顶加群 (DingTalk Pin-to-Top Join Group)

Xposed / LSPosed 模块，在钉钉群设置页注入菜单，自动点击聊天页顶部 OneBox 置顶卡片，循环完成连续加群。

## 功能

| 特性 | 说明 |
|------|------|
| 菜单注入 | 群设置页动态添加「从本群开始加群」「提取本群链接」两个按钮 |
| 模拟点击 | 构造 MotionEvent 注入触摸事件，点击 OneBox 置顶卡片 |
| 自动确认 | 后台线程轮询加群确认页按钮，自动点击「加入」 |
| 状态机循环 | watchdog 500ms 轮询，触摸落空自动补点，死链/过期自动停止 |
| 群追踪 | 全局 ActivityLifecycleCallbacks + ChatMsgActivity.onResume 双路追踪前台群 |
| 链接提取 | 调用 ConversationService.getCode 提取本群邀请链接，复制到剪贴板 |
| 去重保护 | 确认页仍在前台时禁止重复点卡片，避免同一个群被加两次 |
| 文件日志 | 按日期切分的摘要日志 + 详细系统日志，写入钉钉私有目录 |

## 已测试环境

| 项目 | 版本 / 型号 |
|------|------------|
| 手机 | Redmi K90 Pro Max (25102RKBEC) |
| Android | 16 |
| 钉钉 | 7.6.55 (versionCode 1168) |
| 框架 | LSPosed |

> 其他钉钉版本可能需要调整硬编码的类名/字段名（如 `ChatMsgActivity.T0`、`JoinGroupConfirmActivity.v0`/`c0`、资源 id `card_view_container`/`tv_verify_error`）。

## 架构

```
MainHook.java         # Xposed 入口，Hook 部署，菜单注入，注册全局 Activity 回调
JoinLoop.java         # 加群循环状态机（watchdog 500ms 轮询、1s 补点、20s 死线）
CardTapper.java       # 查找 card_view_container，注入触摸事件，返回三态(成功/无容器/未布局)
AutoConfirm.java      # hook JoinGroupConfirmActivity.onCreate，自动点确认，过期/审批检测
ActivityTracker.java  # Application.ActivityLifecycleCallbacks，可靠追踪 topActivity/curCid
TopTracker.java       # hook ChatMsgActivity.onResume，更新 curCid（补充 ActivityTracker）
SettingsResumeHook.java # 群设置页 onResume 时注入按钮
ProbeLink.java        # 反射调用 ConversationService.getCode 提取邀请链接
Toaster.java          # 主线程 Toast 封装
FileLogger.java       # 按日期切分的文件日志（summary/system 两类）
```

### 加群流程

1. 群设置页点「🔁 从本群开始加群」→ 输入数量
2. 关闭设置页，延迟 400ms 后开始第一跳
3. `CardTapper` 点击聊天页顶部🚕 OneBox 卡片
4. 卡片打开加群确认页 → `AutoConfirm` 自动点「加入」
5. `advanceFrom` 从确认页 `c0` 读取新群 cid，推进状态机
6. 延迟 500ms 后从新群点下一张卡片；若确认页仍在前台则等 700ms 重试，绝不重复点
7. 循环直到达到设定数量，或遇到过期二维码/死链/需审批群

## 构建

```bash
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release-unsigned.apk
# 需自行签名后安装
```

## 使用

1. 安装 LSPosed / Xposed 框架
2. 安装本模块 APK
3. 在 LSPosed 中启用模块，作用域勾选钉钉
4. 强制停止钉钉后重新打开
5. 进入任意群聊 → 群设置页，即可看到控制按钮

## 注意事项

- 本项目仅限学习研究使用
- 模块日志写入 `/data/data/com.alibaba.android.rimet/files/lspilot/<uid>/log/`（需 root 查看），详细日志同时输出到 logcat（tag: `PinJoin` / `JOIN_LOOP` / `AUTO_CONFIRM` / `CARD_TAP`）
