# 钉钉置顶加群 (DingTalk Pin-to-Top Join Group)

Xposed / LSPosed 模块。读取钉钉群顶部置顶卡片（OneBox / TopInteraction）中的下一群邀请链接，全后台自动连续加群，无需模拟点击 UI。

> 作者 / 项目地址：https://github.com/chern91552/dingtalk-pin-join

## 功能

| 特性 | 说明 |
|------|------|
| 静默加群 | 群设置页注入「开始静默加群」，纯后台读链接→校验→加群，无前台点击 |
| 置顶链接读取 | 通过 `getChatContextByTypes(TOP_INTERACTION)` 拉取置顶卡片，经 WaveCard SDK 取 `cardData` |
| 多置顶支持 | 一个群多张置顶（如公告卡在前、链接卡在后）时，批量拉取全部并逐张扫链接，命中即用 |
| 双键兼容 | 链接键兼容 `ob_linkUrl`（内容卡）与 `nexturl`（`.schema` 拼车卡），优先取前者 |
| 邀请码校验 | 从链接提取 `code`/`origin`，调 `verifyCodeV2` 解析目标群 cid，再 `addGroupMemberByQrcodeV4` 加群 |
| 去重跳过 | 已在群内（`getConversationFromMemory` 命中）自动跳过，计入「已加过」 |
| 未读标记清除 | 加入新群后清红点 / `firstJoin` 标记，队尾群多趟补清防止服务端晚下发复活标记 |
| 群追踪 | 全局 ActivityLifecycleCallbacks + ChatMsgActivity.onResume 双路追踪前台群 |
| 链接提取 | 调用 ConversationService.getCode 提取本群邀请链接，复制到剪贴板 |
| 文件日志 | 按日期切分的摘要日志 + 详细系统日志 |

## 已测试环境

| 项目 | 版本 / 型号 |
|------|------------|
| 手机 | Redmi K90 Pro Max (25102RKBEC) |
| Android | 16 |
| 钉钉 | 7.6.55 (versionCode 1168) |
| 框架 | LSPosed |

> 其他钉钉版本可能需要调整硬编码的混淆类名/字段名（如 `q8i`、`nf2`、`rwm`、`WaveCardSDKManager`、`ChatContextRequestModel`、`ApiEventListener`、`WaveCardModelListCallBack` 等）。这些名字会随钉钉版本变化，升级时需重新反编译对齐。

## 架构

```
MainHook.java              # Xposed 入口，Hook 部署，菜单注入，注册全局 Activity 回调
SilentJoin.java            # 静默加群状态机：链接→校验→加群→清未读→下一跳，计数与结果提示
NextGroupFetcher.java      # 轮询 getChatContextByTypes(TOP_INTERACTION) 拉置顶卡片（重试 3 次）
ChatContextListenerProxy.java # 解析 chatcontext 回调，收集全部可用置顶卡（boxObjectList）
WaveCardFetcher.java       # 经 WaveCard SDK(rwm.q) 批量拉卡片数据，逐张扫 cardData 里的下一群链接
WaveCardCallbackProxy.java # WaveCardModelListCallBack 回调代理
VerifyCallbackProxy.java   # verifyCodeV2 回调代理：code→目标群 cid
JoinCallbackProxy.java     # addGroupMemberByQrcodeV4 回调代理
UnreadClearer.java         # 清新会话红点/firstJoin 标记，队尾群多趟补清
ActivityTracker.java       # Application.ActivityLifecycleCallbacks，追踪 topActivity/curCid
TopTracker.java            # hook ChatMsgActivity.onResume，更新 curCid
SettingsResumeHook.java    # 群设置页 onResume 时注入按钮
ProbeLink.java             # 反射调用 ConversationService.getCode 提取本群邀请链接
CardTapper.java / JoinLoop.java / AutoConfirm.java # 旧的模拟点击加群路径（保留）
Toaster.java               # 主线程 Toast 封装
FileLogger.java            # 按日期切分的文件日志（summary/system 两类）
```

### 静默加群流程

1. 群设置页点「开始静默加群」→ 输入数量 N
2. `NextGroupFetcher` 对当前群发起 `getChatContextByTypes(TOP_INTERACTION)`（无卡最多重试 3 次）
3. `ChatContextListenerProxy` 收集 `boxObjectList` 里**全部**可用置顶卡
4. `WaveCardFetcher` 一次性批量拉取全部卡片数据（`rwm.q` 为 list API）
5. 回调逐张扫 `cardData`，优先 `ob_linkUrl` 回退 `nexturl`，取到第一条链接即用
6. 从链接提取 `code`/`origin` → `verifyCodeV2` 解析目标群 cid
7. 已在群内则跳过（计入「已加过」）；否则 `addGroupMemberByQrcodeV4` 加群
8. 清除新会话未读标记；以目标群为新起点重复 2–7，直到达到 N 或链接过期/无置顶卡

## 构建

```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
# 或 assembleRelease 后自行签名
```

## 使用

1. 安装 LSPosed / Xposed 框架
2. 安装本模块 APK
3. 在 LSPosed 中启用模块，作用域勾选钉钉（`com.alibaba.android.rimet`）
4. 强制停止钉钉后重新打开
5. 进入任意群聊 → 群设置页，即可看到控制按钮

## 注意事项

- 本项目仅限学习研究使用
- 模块日志写入 `/storage/emulated/0/Android/media/com.alibaba.android.rimet/PinJoin/<uid>/log/`（无 root 可直接 `adb pull`），详细日志同时输出到 logcat（tag: `PinJoin` 等）
