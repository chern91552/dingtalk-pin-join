# 钉钉置顶加群 (DingTalk Pin-to-Top Join Group)

Xposed / LSPosed 模块。读取钉钉群顶部置顶卡片（OneBox / TopInteraction）中的下一群邀请链接，全后台自动连续加群，无需模拟点击 UI。

> 作者 / 项目地址：https://github.com/chern91552/dingtalk-pin-join

> **完全免费，禁止倒卖。** 使用相同代码需标注原作者（保留上方项目地址）。

## 功能演示

![群设置页功能演示](docs/demo.jpg)

## 功能

| 特性 | 说明 |
|------|------|
| 置顶加群 | 群设置页注入「置顶加群」，前台模拟点击卡片连续加入群聊（旧路径，保留） |
| 静默加群 | 群设置页注入「静默加群」，纯后台读链接→校验→加群，无前台点击 |
| 置顶查证 | 群设置页注入「置顶查证」，沿置顶链路逐群查公益树开通状态、证书信息和领证情况 |
| 群链接 | 群设置页注入「群链接」，调用 ConversationService.getCode 提取本群邀请链接，复制到剪贴板 |
| 置顶链接读取 | 通过 `getChatContextByTypes(TOP_INTERACTION)` 拉取置顶卡片，经 WaveCard SDK 取 `cardData` |
| 多置顶支持 | 一个群多张置顶（如公告卡在前、链接卡在后）时，批量拉取全部并逐张扫链接，命中即用 |
| 链接提取增强 | 候选键从 2 个扩展到 8 个（`ob_linkUrl`/`nexturl`/`ob_jumpUrl` 等），正则兜底扫 `joingroup`，URLDecode 兼容 encode |
| 邀请码校验 | 从链接提取 `code`/`origin`，调 `verifyCodeV2` 解析目标群 cid，再 `addGroupMemberByQrcodeV4` 加群 |
| 去重跳过 | 已在群内（`getConversationFromMemory` 命中）自动跳过，计入「已加过」 |
| 失败重试 | 网络超时、系统繁忙等临时错误退避重试 2 次；邀请码过期、需要审批等业务错误立即停止 |
| RPC 看门狗 | 异步 RPC 15 秒超时保护，`AtomicBoolean` Token 确保只有一个终态能胜出，防止回调卡死 |
| 入群通知设置 | 加群成功后自动开启消息免打扰 + 禁止 @所有人通知（可选），等待会话就绪后执行 |
| 未读标记清除 | 加入新群后清红点 / `firstJoin` 标记，队尾群多趟补清防止服务端晚下发复活标记 |
| 任务状态与停止 | 群设置页实时显示任务进度和当前群名，可随时停止；任务 ID 隔离旧异步回调 |
| 群追踪 | 全局 ActivityLifecycleCallbacks + ChatMsgActivity.onResume 双路追踪前台群 |
| 文件日志 | 按日期切分的摘要日志 + 详细系统日志，保留 7 天，每个文件写入版权 BANNER |
| 日志查看 | 群设置页内查看近 7 天日志，支持日期切换、关键词搜索、敏感信息脱敏、一键复制 |
| 分类统计 | 摘要统一记录成功、已加过、无置顶、链接失效、需要审批、请求失败，任务结束输出完整统计 |

## 已测试环境

| 项目 | 版本 / 型号 |
|------|------------|
| 手机 | Redmi K90 Pro Max (25102RKBEC) |
| Android | 16 |
| 钉钉 | 7.6.55 (versionCode 1168) |
| 框架 | LSPosed |

> 其他钉钉版本可能需要调整硬编码的混淆类名/字段名（如 `q8i`、`nf2`、`rwm`、`byh`、`WaveCardSDKManager`、`ChatContextRequestModel`、`ApiEventListener`、`WaveCardModelListCallBack` 等）。这些名字会随钉钉版本变化，升级时需重新反编译对齐。

## 架构

```
MainHook.java                  # Xposed 入口，Hook 部署，菜单注入，注册全局 Activity 回调
SilentJoin.java                # 静默加群状态机：链接→校验→加群→清未读→下一跳，计数与结果提示
SilentJoinStatusView.java      # 静默加群任务进度与停止操作
ForestAudit.java               # 置顶查证状态机：逐群查公益树详情→证书列表→证书详情→领证状态
ForestAuditNextFetcher.java    # 置顶查证：解析下一群置顶卡片链接（复用 WaveCardFetcher）
ForestAuditStatusView.java     # 置顶查证任务进度与停止操作
GroupNotificationSettings.java # 入群后自动消息免打扰 + 禁止 @所有人
LwpClient.java                 # LWP RPC 客户端（JsapiLwpCall 通道）
RpcWatchdog.java               # 异步 RPC 超时看门狗（15s 超时保护）
RetryPolicy.java               # RPC 临时错误分类与 1s/2s 退避重试
NextGroupFetcher.java          # 轮询 getChatContextByTypes(TOP_INTERACTION) 拉置顶卡片（重试 3 次）
ChatContextListenerProxy.java  # 解析 chatcontext 回调，收集全部可用置顶卡（boxObjectList）
WaveCardFetcher.java           # 经 WaveCard SDK(rwm.q) 批量拉卡片数据，逐张扫 cardData 里的下一群链接
WaveCardCallbackProxy.java     # WaveCardModelListCallBack 回调代理
VerifyCallbackProxy.java       # verifyCodeV2 回调代理：code→目标群 cid
JoinCallbackProxy.java         # addGroupMemberByQrcodeV4 回调代理
SafeCheckCallbackProxy.java    # preJoinGroupSafeCheck 回调代理：安全检查 token 提取
UnreadClearer.java             # 清新会话红点/firstJoin 标记，队尾群多趟补清
ActivityTracker.java           # Application.ActivityLifecycleCallbacks，追踪 topActivity/curCid
TopTracker.java                # hook ChatMsgActivity.onResume，更新 curCid
SettingsResumeHook.java        # 群设置页 onResume 时注入按钮
ProbeLink.java                 # 反射调用 ConversationService.getCode 提取本群邀请链接
CardTapper.java / JoinLoop.java / AutoConfirm.java # 旧的模拟点击加群路径（保留）
Toaster.java                   # 主线程 Toast 封装
FileLogger.java                # 按日期切分的文件日志（summary/system 两类），保留 7 天
LogViewer.java                 # 群设置页日志查看器（日期切换、搜索、脱敏、复制）
```

### 静默加群流程

1. 群设置页点「静默加群」→ 输入数量 N
2. `NextGroupFetcher` 对当前群发起 `getChatContextByTypes(TOP_INTERACTION)`（无卡最多重试 3 次）
3. `ChatContextListenerProxy` 收集 `boxObjectList` 里**全部**可用置顶卡
4. `WaveCardFetcher` 一次性批量拉取全部卡片数据（`rwm.q` 为 list API）
5. 回调逐张扫 `cardData`，8 个候选键优先取值，正则兜底扫 `joingroup`
6. 从链接提取 `code`/`origin` → `verifyCodeV2` 解析目标群 cid
7. 已在群内则跳过（计入「已加过」）；否则 `addGroupMemberByQrcodeV4` 加群
8. 加群成功后 `GroupNotificationSettings` 自动开启消息免打扰
9. 清除新会话未读标记；以目标群为新起点重复 2–8，直到达到 N 或链接过期/无置顶卡

### 置顶查证流程

1. 群设置页点「置顶查证」→ 输入数量 N
2. `LwpClient` 调用 `getDetailNew` 查询当前群公益树详情
3. 未开通公益树 → 记录并跳到下一群；已开通 → 继续查证书
4. 调用 `queryCertificateList` 查证书列表 → 调用 `queryCertificate` 查证书详情
5. 解析树种、证书编号、创建时间、本人是否已领证
6. `ForestAuditNextFetcher` 解析置顶卡片中的下一群链接 → `verifyCodeV2` 跳转
7. 以目标群为新起点重复 2–6，直到达到 N 或链接过期/不在群内

## 构建

```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
# 或 assembleRelease，签名配置写入 local.properties 后自动签名
```

### 签名配置

在项目根目录创建 `local.properties`，添加签名参数：

```properties
pinjoin.storeFile=pinjoin.keystore
pinjoin.storePassword=android
pinjoin.keyAlias=pinjoin
pinjoin.keyPassword=android
```

未配置时仍可正常构建（输出未签名 APK）。

## 使用

1. 安装 LSPosed / Xposed 框架
2. 安装本模块 APK
3. 在 LSPosed 中启用模块，作用域勾选钉钉（`com.alibaba.android.rimet`）
4. 强制停止钉钉后重新打开
5. 进入任意群聊 → 群设置页，即可看到四个功能卡片

## 注意事项

- 本项目仅限学习研究使用
- 模块日志写入 `/storage/emulated/0/Android/media/com.alibaba.android.rimet/PinJoin/<uid>/log/`（无 root 可直接 `adb pull`），详细日志同时输出到 logcat（tag: `PinJoin` 等）
