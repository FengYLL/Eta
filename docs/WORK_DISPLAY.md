# 独立工作屏（实验性）

工作屏使用当前 Android 用户的原应用、原数据与原登录状态，不创建分身、工作资料或 Android 虚拟机。需要 Android 14+、Eta 无障碍服务、LSPosed 的系统框架作用域，安装或更新模块后需重启手机。默认关闭。

## 使用

1. 打开 Eta 的「工具能力 → 工作屏（实验性）」。
2. 点击「创建 / 检查」。不支持的 ROM、未加载的系统守卫会明确失败，不回退主屏。
3. 开启「新任务使用独立工作屏」，返回对话开始任务。首次 GUI 工具调用会自动建屏，不必预先点击「创建 / 检查」。普通问答不会创建显示器。这个开关在 Runtime 接收新任务时冻结；运行中切换不会改动当前任务的显示器。
4. 通过「工具能力 → 工作屏」或后台执行通知进入控制页面，实时观看应用操作，可暂停、继续、停止任务、接管、关闭。
5. 点击「返回 · 后台继续执行」、系统返回键或切换其他应用，工作屏和任务继续保留。点击通知可重新观看同一会话。只有明确停止任务、关闭工作屏、接管或连接失效才结束或暂停执行。

开始任务即允许将目标应用现有的全屏任务迁入副屏，主屏可能回到桌面或下层应用。执行期间 ETA 优先占用相同应用，主屏可使用其他应用。分屏、画中画、嵌套任务及无法安全迁移的混合任务拒绝迁移。

任务完成或取消后保留副屏页面。主动点击「接管到主屏」，或在完成后从桌面/最近任务打开相同应用，允许回主屏。接管可能触发 Activity 重建，副屏不再保留同一可交互页面。关闭副屏会销毁其页面，不执行包级 force-stop，不清除应用数据。

## 实现边界

| 组件 | 作用 |
|---|---|
| `WorkDisplayHooks` / `WorkDisplayBroker` | LSPosed 在 system_server 安装窄范围 Binder 服务和任务路由守卫 |
| `DisplaySessionStore` / `DisplayLease` | 显示会话、运行租约、代次、暂停、结果保留、接管和资源引用 |
| `DisplayLocalTools` | 在同一个 run 中统一处理所有 GUI 工具，拒绝主屏后端回退 |
| `DisplayAccessibility` | 只读取目标 display 的窗口和节点，用 `takeScreenshot(displayId)` 获取原始截图 |
| `DisplayToolPolicy` | 模型工具目录和执行边界共用允许列表；限制不支持的参数 |
| `WorkDisplayActivity` | 用户控制入口、保持比例的 TextureView 实时观看；页面退出只卸载观看输出 |
| `PreviewSurfaceLease` | 独立的观看代次和心跳；旧页面不能卸载新页面，观看超时恢复后台输出 |

显示器固定为 900×1600、320 dpi。借鉴 Operator-on-Android 的前后台输出切换方式：观看时把同一个 VirtualDisplay 绑定到页面 TextureView 的 Surface；页面暂停/销毁时先绑定回常驻 `ImageReader`，再释放观看 Surface。后台持续消费 buffer，不把 Surface 置空，不释放 VirtualDisplay，不改变模型的截图与输入坐标。

观看与执行使用独立租约：观看页面每秒续期；5 秒没有续期时，由系统端看门狗恢复后台输出。旧页面的延迟卸载不能影响新页面；Surface 销毁时等待切换完成，Binder 错误时延迟释放至看门狗处理之后。显示器和任务仍由后台前台服务持有，停止任务后保留结果，关闭工作屏才释放。App 整体被强停后不能继续执行。

截图仍通过 `takeScreenshot(displayId)` 获取，不依赖前台观看画面；图片编码与模型调用留在 App 进程。此实现直接使用既有 LSPosed 系统能力，不另行安装 Shizuku，也不启动拥有任意命令执行能力的 Root daemon。

Binder 连接通过 signature 权限保护的广播发放，校验发送 UID；每次事务再次验证真实 Binder 调用 UID、当前用户、会话、运行 ID 与动作代次。显示器创建需同时保留 TRUSTED、OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED，运行前重复验证显示器有效性与几何。

暂停撤销特权端未提交动作，长手势逐段检查租约并以 CANCEL 收尾。App 端节点提交与暂停确认、完成和主动接管共用门闩；已提交 Android 的动作无法撤销，不会自动重放结果不明的动作。恢复后旧观察不可继续使用。

显示资源与执行资源分离：「停止执行」停止运行，保留结果页；「关闭工作屏」才释放显示。App 进程死亡后通过 Binder death 回收显示器，不能保证未保存页面可恢复。系统守卫失效时撤销操作并异步回收资源，避免坏 Hook 阻塞整个系统。

## 已知限制与真机验收

普通输入法只服务顶层焦点显示器。工作屏禁止抢走主屏顶层焦点，因此使用无障碍节点 `ACTION_SET_TEXT`，不采用共享剪贴板或 IME 回退。不可编辑节点、密码追加、不支持节点操作、安全截图及需要主屏的页面会暂停，等待用户主动接管。

通知、音频、相机、麦克风和应用后台服务仍属于同一 Android 用户，不能当成独立系统资源。终端、任意命令、MCP、内置浏览器、全局 Home/最近任务/通知栏、剪贴板与系统设置操作不向独立 run 开放。当前只提供定向 BACK、http(s) URI 和有界 GUI 操作。

当前页面支持实时观看，不向工作屏转发用户触摸，避免与 Agent 并发输入；手动操作请先接管。旋转观看页面只改变画面的缩放和留边，不改变工作屏的固定竖屏坐标。工作屏几何本身改变后会拒绝操作，需要关闭后重建。退到后台与锁屏不同：锁屏后拒绝继续 GUI 操作，需解锁并继续。非标准 ROM 的隐藏 API、Activity 启动与任务迁移路径必须实机验证；源码编译与单元测试不证明这些路径在具体手机上可用。

首轮真机验收必须记录：

- 建屏后真实 flags、userId、displayId，以及关闭后的资源回收。
- 前台实时观看 → 返回聊天/桌面并使用其他应用 → 从通知重新观看，确认 displayId、应用任务和执行进度延续。
- 反复旋转/退出/重开观看页面；旧页面延迟回调不卸载新页面；无心跳超过 5 秒恢复后台输出。
- 前台观看与后台模式下分别执行截图、点击、滑动、文本输入，确认截图保持 900×1600 且坐标一致。
- 主屏连续中文编辑时，副屏输入中文不抢焦点、不修改剪贴板。
- 主屏滑动与副屏点击/长手势并发，暂停后不再提交后续事件。
- 全屏现有应用迁移与登录态保留；分屏/PiP 拒绝。
- 执行期间 Launcher、最近任务、通知、深链不能把占用应用抢回主屏。
- 副屏应用 A → B → 浏览器/文件选择器；不兼容路径暂停。
- 完成后结果页保留；Launcher/最近任务主动接管；关闭不迁回主屏。
- 暂停/恢复、取消、App 杀进程、系统重启、Binder 断线、旧观察及显示 ID 复用。
- 权限/支付/安全页面、锁屏、切换 Android 用户、窗口几何改变的失败行为。

## 构建和研究依据

本地验证命令：`./gradlew :app:testDebugUnitTest :app:assembleDebug`。`PreviewSurfaceLeaseTest` 覆盖后台恢复、旧页面卸载、先卸载后到达的挂接、心跳超时、挂接失败和资源释放；`DisplayLeaseTest`、`DisplayPauseTest` 覆盖运行、暂停、接管和旧观察失效。GitHub Actions 的 `Eta Build` 工作流构建 Debug 和 Release APK。没有配置 Release 签名 Secrets 时使用临时 CI 证书；不同运行的临时签名不能保证覆盖安装。

主要参考：[Operator-on-Android](https://github.com/xjyzs/Operator-on-Android/tree/1aad5cac38f4c1833397f03e2e104e2f5aebaa49) 的 `InputControlUtils.kt`、`VirtualDisplayViewer.kt`、`VirtualDisplayController.kt`，以及 [Ynkcc/VirtualDisplay](https://github.com/Ynkcc/VirtualDisplay/tree/0390d8f07ac4071658e77bff9b0974c57f16a186)、[scrcpy](https://github.com/Genymobile/scrcpy)、[AOSP Android 14 Display](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/view/Display.java)。没有打包参考项目的完整 App 或远程命令服务；Operator 的 MIT 声明保留在 `assets/licenses/operator-on-android.txt` 和 [第三方说明](THIRD_PARTY_NOTICES.md)。
