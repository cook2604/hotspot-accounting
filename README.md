# 热点分账 (Hotspot Accounting)

按 **MAC 地址**统计本机热点下每台客户端设备的真实流量，用于按量回收费用与数据统计。

面向 **HyperOS / MIUI（Android 13+）+ KernelSU / APatch** 的红米机型。

界面采用 **iOS 26「Liquid Glass」风格的半透明材质**：浮动玻璃卡片、真实背景模糊、
深色渐变底衬上的彩色光晕。设计说明见下方「界面设计」一节。

---

## 它解决什么问题

普通流量统计 App 只能看到「本机总共用了多少」，无法回答**「连我热点的张三用了 8.2 GB，李四用了 3.1 GB」**。

这个 App 的答案是：**在 Linux 内核里装按 MAC 的字节计数器**，直接读内核计数，而不是靠估算或采样。

```
                     ┌──────────── 内核（root 下发）────────────┐
   客户端设备          │  table bridge hsacc                    │
   张三的手机  ──────► │   chain prerouting                     │
   aa:bb:cc:..        │     ether saddr aa:bb:.. counter  → 上行 │
                      │     ether daddr aa:bb:.. counter  → 下行 │
                      └────────────────┬───────────────────────┘
                                       │ 每 3 秒读一次计数器
                                       ▼
                              差分 → 按小时分片落库（Room）
                                       │
                                       ▼
                          实时看板 / 日周月报表 / 按单价计费 / CSV 导出
```

### 为什么以 MAC 为身份，而不是 IP

手机连热点拿到的 IP 会随 DHCP 续租而变。如果按 IP 记账，客户端换个 IP 就会被算成一台「新设备」，
账目立刻错位。MAC 在二层是稳定的，所以**计数和计费都锚定在 MAC**。

### 为什么用内核计数器，而不是读 `/proc/net/dev`

`/proc/net/dev` 只有网卡总数，没有按设备拆分。要按设备拆分，就必须在数据包路径上放计数器——
这正是 nftables `counter` 做的事。

---

## 功能

| 功能 | 说明 |
|---|---|
| **实时看板** | 在线设备、各自上传/下载/合计、实时速率、**应收合计**、最后活动时间、**接入方式** |
| **历史统计** | 今天 / 昨天 / 本周 / 本月 / 上月 / 全部，按设备汇总，顶部显示用量与**应收合计** |
| **计费** | 每台设备可单独设单价（元/GB）；另有**全局默认单价**，新设备自动套用，无需逐台填写 |
| **设备备注** | 把 MAC 命名为「张三的手机」，支持账务备注与是否纳入计费 |
| **CSV 导出** | 一键导出当前区间明细，含合计 GB、单价、应收与总计，可直接给 Excel |
| **多种共享方式** | Wi-Fi 热点、**USB 共享**、蓝牙共享、以太网共享；可同时开启并分别统计 |
| **断网控制** | 按 MAC 切断某台设备的网络（需 ebtables） |
| **限速控制** | 按设备限上下行带宽，**自动作用于该设备实际所在的接口**（需 tc） |
| **计数器自检** | 主动验证规则是否真的在计数，而不是假装成功 |
| **开机自启** | 重启手机后自动恢复统计 |

### 支持的共享方式

| 共享方式 | 典型接口名 | 支持 |
|---|---|---|
| Wi-Fi 热点 | `ap0`、`swlan0`、`softap0`、`wlan1` | ✅ |
| **USB 共享（手机给电脑供网）** | `rndis0`、`usb0`、`ncm0` | ✅ |
| 蓝牙共享 | `bt-pan`、`bnep0` | ✅ |
| 以太网共享（USB 网卡等） | `eth1`、`eth2`… | ✅ |
| 多方式同时开启 | 例如 `ap0` + `rndis0` | ✅ 分别识别、分别限速 |

**USB 共享不需要额外适配，因为计数发生在二层。** 客户端流量是*转发*的，`bridge` 层的
`prerouting` hook 对 USB 网卡和 Wi-Fi 网卡一视同仁——都按 MAC 计数，与接口类型无关：

```
table bridge hsacc {
  chain prerouting {              # 网桥成员包括 ap0 与 rndis0
    ether saddr <mac> counter     # 上行：无论客户端走 WiFi 还是 USB
    ether daddr <mac> counter     # 下行：同上
  }
}
```

两点需要说明的边界：

- **接口名因机型而异**：部分机型 USB 共享接口是 `rndis0`，部分是高通的 `usb0`，
  较新的是 `ncm0`。这三种前缀都已覆盖，界面上的「共享接口」会显示实际识别到什么。
- **反方向不适用**：若是*电脑给手机*供网（手机作为 USB 客户端），
  那是手机自身的用量，不属于「共享给某台客户端的流量」，本应用不统计这类流量。

**限速按设备所在接口生效。** 这是 USB 支持里最容易出错的一处：同时开着 Wi-Fi 和 USB 共享时，
如果限速规则固定下发给第一个接口，USB 设备的限速会打到 Wi-Fi 网卡上——对该设备**完全无效**。
所以限速前会先解析目标设备实际在哪个接口上。

### 计费是怎么算的

```
应收 = 用量字节 ÷ 1024³ × 单价(元/GB)
```

1 GB = 1024 MB（与 Android 系统显示用量的口径一致）。计费逻辑集中在 `data/AppSettings.kt`
的 `Billing` 对象里，实时页和统计页共用同一份实现，因此两处**不可能算出不同的结果**。

**单价有两种设置方式：**

| 方式 | 位置 | 适用场景 |
|---|---|---|
| **全局默认单价** | 设置页 → 默认单价 | 所有设备同价（例如都是 3 元/GB），设一次即可 |
| **设备单独单价** | 实时页 → 该设备「设置」 | 个别设备价格不同，或某台要免费 |

默认单价只作用于**新接入**的设备。已手动设过价的设备不会被覆盖；
如需把默认价补给历史设备，在设置页勾选「同时套用到当前未定价的设备」——
这个操作也**只影响 `price_per_gb = 0` 的设备**，你手动设过的价（含故意设的 0）都不会被改动。

---

## 技术要点

### 计数层：nftables bridge family

```
table bridge hsacc {
  chain prerouting {
    type filter hook prerouting priority -300; policy accept;
    ether saddr <mac> counter comment "hsacc:up:<mac>"     # 上行
    ether daddr <mac> counter comment "hsacc:down:<mac>"   # 下行
  }
}
```

- **选 bridge family 的原因**：热点流量是*转发*的，不是本机收发。本机 output 计数器看不到它。
  而在二层抓，上下行都能看到客户端真实 MAC，且**不受源 NAT 影响**（SNAT 改 IP，不改转发帧的以太头）。
- **每个 MAC 两条规则**：单个 hook 只能看到一个方向，所以上行用 `ether saddr`、下行用 `ether daddr`。
- **priority -300**：抢在 `br_netfilter` 的分片重组之前，连分片和非 IP 协议也能计入。

内核不支持 bridge family 时，自动退回 `iptables -m mac`（功能略弱，见下）。

### 计数正确性：三重防错

1. **增量差分**：内核给的是单调累计值。每次读取减去上次存的值，得到本次增量。
2. **重置检测**：计数器变小 = 规则被重建（热点重启 / 手机重启）。此时新建 epoch，
   把新读数当作新基线，**既不重复计费也不丢账**。
3. **事务落库**：增量先写内存，`flush` 时在一个事务里累加到小时分片。
   只在事务提交成功后才清空内存；失败则把增量放回重试。因此崩溃最多丢一个采样周期，
   不会出现「扣了钱但没记录」。

### 设备发现：三个来源合并

| 来源 | 提供什么 |
|---|---|
| `ip neigh`（IPv4 + IPv6） | **谁真的在线**（含只有 IPv6 链路本地地址的设备） |
| DHCP 租约文件 | 设备**主机名**，让未命名设备可辨识 |
| `dumpsys tethering` | 兜底（部分 ROM 租约只在内存里） |

三个来源按 MAC 合并，任一来源缺字段都可以被其他来源补齐。

---

## 界面设计：iOS 26 Liquid Glass 风格

界面仿照 iOS 26 的半透明材质：浮动玻璃卡片、真实背景模糊、深色渐变底衬上的彩色光晕。
设计代码集中在 `ui/glass/`，与业务逻辑完全解耦。

### 「玻璃」是怎么做出来的（以及一个必须说清的坑）

**`Modifier.blur()` 模糊的是组件自身，不是它背后的内容** —— 所以单用它做不出毛玻璃。
可行的方法是：在背景上铺一层**半透明色块**，再把这层色块本身模糊掉。因为它透明，
背后的颜色会透过来并变柔，视觉上就是毛玻璃。文字放在内层 Box 里，因此**不受模糊影响**。

> 最初想把 `android.graphics.RenderEffect` 直接赋给 `graphicsLayer`，**编译不通过**：
> 那个位置的类型是 Compose 自己的 `RenderEffect`，不是 Android 框架的那个。
> 改用 Compose 的 `Modifier.blur()` 后既正确又更简单 —— 它在 **API 31 以下自动变成无操作**
> （不会崩），因此不需要自己写版本判断。

### 三个刻意的设计决定

| 决定 | 原因 |
|---|---|
| **底色固定深色**，不跟随系统浅色 | 白色半透明玻璃放在浅色背景上会变成灰扑扑的方块，模糊带来的层次感完全消失 |
| **默认关闭 Material You（动态取色）** | 光晕由 `primary`/`secondary`/`tertiary` 生成；从壁纸取的颜色通常偏灰，透过几层半透明后几乎看不见。仍可用 `dynamicColor = true` 打开 |
| **背景是渐变 + 三个彩色光晕，而非纯色** | 玻璃只有在背后有东西时才看得见。纯色背景下，半透明卡片与一块染色面板毫无区别 |

光晕位置是**固定**的而非动画 —— 移动的光晕会让每块玻璃在重组时不断闪烁。

### 视觉语言

| 元素 | 处理方式 |
|---|---|
| 卡片 | 浮动玻璃面板，带顶部高光与 1px 发光描边，这是「材质边界」的关键 |
| 底部标签栏 | 浮空、不贴屏幕边缘，让背景从四周露出来；选中项用一小块玻璃药丸标记 |
| 标题 | 「大标题」置于内容流中，而非实心顶栏，避免挡住背景 |
| 区间选择器 | 玻璃芯片；选中态用**更亮的色调**表达，而不是实心填充（实心会在玻璃上「打个洞」） |
| 分隔线 | 两端渐隐的发光细线，代替实心横线 |
| 金额 | 单独用暖绿色，与强调色区分 |

### 已知取舍

- **API 31 以下没有真实模糊**，只有半透明 + 高光。视觉接近，但不如 31+ 通透。
- **模糊有渲染开销**。列表里每张卡片都是一次模糊，设备多时比纯色界面更耗电。
  若实测发热或掉帧明显，调小 `GlassMaterial.blurRadius`，或对列表项改用
  `ULTRA_THIN`（模糊半径更小）。

---

## 目录结构

```
APP/
├─ app/                                  Android 应用
│  └─ src/main/java/com/hotspot/accounting/
│     ├─ MainActivity.kt                 入口
│     ├─ App.kt                          Application
│     ├─ root/RootShell.kt               持久 root shell（自研，不依赖 libsu）
│     ├─ core/
│     │  ├─ CounterBackend.kt            nftables / iptables 两套计数后端
│     │  ├─ CounterManager.kt            差分、重置检测、落库
│     │  ├─ DeviceDiscovery.kt           客户端发现 + MAC 厂商库
│     │  └─ NetControl.kt                断网 / 限速（可回滚）
│     ├─ data/                           Room 实体、DAO、仓库
│     ├─ service/CollectorService.kt     前台服务（保活统计）
│     ├─ service/BootReceiver.kt         开机自启
│     └─ ui/                             Compose 界面
├─ tools/                                自包含构建工具链
│  ├─ run-all.ps1                        ★ 一条命令跑完安装+编译
│  ├─ bootstrap.ps1                      装 JDK + Gradle
│  ├─ sdk-setup.ps1                      装 Android SDK 组件（直接下载 + SHA-1 校验）
│  ├─ write-licenses.ps1                 生成 SDK 许可文件（AGP 必需）
│  ├─ build.ps1                          编译 APK
│  ├─ check-project.ps1                  工程静态自检（无需 SDK，秒级）
│  ├─ download.js                        支持断点续传的下载器
│  ├─ discover.js / dump-repo.js / probe-maven.js / probe-mirrors.js / resolve-archive.js
│  │                                     版本与下载地址探测脚本
│  └─ env.ps1                            生成的环境变量
└─ docs/设备端自查.md                     手机侧能力自查与诊断对照表
```

---

## 构建产物（已验证）

```
app\build\outputs\apk\debug\app-debug.apk
  16,620,197 字节 (15.85 MB)
  sha256 44de58ef954842ed0446b0a479d8958db6cc7628b6cc752c5a855be407f6bc84
```

交付前实际执行并通过的验证：

| 验证项 | 结果 |
|---|---|
| 全量重建（删除 `app/build` 后重编） | BUILD SUCCESSFUL |
| `apksigner verify` | 通过 —— v2 签名有效，可安装 |
| `zipalign -c 4` | 通过 |
| ZIP 结构 | 121 个条目，8 个 dex，`classes.dex` 与 `resources.arsc` 均在 |
| `aapt2 dump badging` | 包名/版本/权限/启动 Activity 全部正确 |
| 中文字符串 | 应用名「热点分账」编码正确（UTF-8 无 BOM） |
| 清单组件 | MainActivity / CollectorService(前台服务) / BootReceiver / FileProvider 均已声明 |

---

## 构建

**已在本机验证可用的完整命令：**

```powershell
powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\build.ps1 -Task assembleDebug -UseCnMirrors
```

产物：

```
H:\ddaa\APP\app\build\outputs\apk\debug\app-debug.apk     (15.9 MB)
```

**为什么必须加 `-UseCnMirrors`**：实测从 `dl.google.com` 拉 AGP 的 jar 只有 **82 KB/s**，
Gradle 会因 `Read timed out` 失败；换成阿里云镜像后达到 **10.5 MB/s（快 128 倍）**，依赖秒下。
镜像只是**前置**而非替换，某个包在镜像上缺失时 Gradle 仍会回落到上游，不会把能成功的构建搞坏。

**首次从零搭建（换机器时用）：**

```powershell
powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\run-all.ps1 -UseCnMirrors
```

它按顺序完成：装工具链 → 装 SDK 组件 → 编译。**可重复运行**：已完成的步骤会跳过，
中断的下载会断点续传，失败后直接重跑即可。

### 本机环境必须做的三处重定向（已固化进 build.ps1）

这套环境有三个默认位置不可用，脚本已自动规避。换机器若遇到同样症状，可直接对照：

| 症状 | 原因 | 处理 |
|---|---|---|
| `Could not initialize native services` / `Failed to load native library 'native-platform.dll'` | 默认 `%USERPROFILE%\.gradle` 无法初始化原生服务 | `GRADLE_USER_HOME` 指向工作区 `.gradle-home` |
| `AccessDeniedException: C:\Users\...\.android\debug.keystore.lock` | 默认 `%USERPROFILE%\.android` 不可写 | `ANDROID_USER_HOME` 指向工作区 `.android-home` |
| 配置阶段卡在 `Still waiting for package manifests to be fetched remotely` | 手工装的 SDK 缺少 `licenses/`，AGP 认为未接受许可而反复联网 | 生成许可文件 + `-Pandroid.builder.sdkDownload=false` |

### 下载器的两个抗抖动设计

这台机器到 `dl.google.com` 的连接实测会在 `130 KB/s → 8 KB/s → 100 KB/s` 之间剧烈波动，
而且**劣化后的连接不会彻底断开**，只是偶尔挤出几个字节。所以 `download.js` 做了三层保护：

1. **断点续传**：用 HTTP `Range` 从已下载字节处继续，重连不丢进度。
2. **吞吐量看门狗**：连接仍在传输但 30 秒窗口内平均速率低于 4 KB/s 时，主动断开并重连续传。
   实测新连接速率远高于已劣化的旧连接（103.6 KB/s vs 0.3 KB/s），重连比干等更快。
   *注意*：判定**从首字节到达才开始计时** —— 首字节延迟实测可超过 20 秒，
   若从连接建立就计时，会把健康连接误杀（这个 bug 真实发生过）。
3. **大小 + SHA-1 双重校验**：残缺或被服务器拼接坏的文件不会被当成成功
   （服务器忽略 `Range` 返回 200 时，直接追加会损坏文件并导致后续 `HTTP 416`，此路径已修正）。

### 手工验证源码（不需要 SDK，1 秒出结果）

```powershell
powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\check-project.ps1
```

检查包名/目录匹配、重复声明、工程内 import 可解析、清单引用类存在、资源引用可解析、
Gradle 依赖自洽，以及 **Room 实体构造参数是否漏写 `val`**
（漏写会导致 KSP 报出误导性的「引用了不存在的类型」，实际发生过一次）。

**分步执行（如需单独控制）：**

```powershell
# 1. 工具链（JDK 17 + Gradle 8.14.5，约 314 MB）
powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\bootstrap.ps1

# 2. Android SDK 组件（platform 35 + build-tools 35，约 122 MB，SHA-1 校验）
powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\sdk-setup.ps1

# 3. 补齐 SDK 许可文件（AGP 需要，否则会反复联网查包清单并卡住）
powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\write-licenses.ps1

# 4. 编译
powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\build.ps1 -UseCnMirrors
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

工具链是**免安装、免管理员**的：全部解压在工作区 `tools/` 下，不污染系统，删目录即可卸载。

### 版本组合

| 组件 | 版本 |
|---|---|
| JDK | Temurin 17.0.20.1 |
| Gradle | 8.14.5 |
| Android Gradle Plugin | 8.13.0 |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Compose BOM | 2025.01.00 |
| Room | 2.6.1 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |

---

## 使用流程

1. 安装 APK，打开 App，点「开始」→ KernelSU 弹出授权框 → 允许。
2. 让设备连上本机热点。
3. 设备会自动出现在实时看板；点「设置」给它命名、设单价。
4. 「统计」页选时间区间看用量与应收，点右上角导出 CSV。
5. 若某台设备流量显示为 0，进「设置」运行**计数自检**，按提示排查。

详细排查见 [docs/设备端自查.md](docs/设备端自查.md)。

---

## 已知限制（如实说明）

| 限制 | 原因 | 影响 |
|---|---|---|
| 部分老内核无 bridge family | 未编译 `CONFIG_NF_TABLES_BRIDGE` | 自动退回 iptables 后端，精度略降 |
| 客户端开「随机 MAC」 | 系统隐私功能会轮换 MAC | 同一台手机会显示为多条记录，需各自命名 |
| 限速功能可能拒绝执行 | 系统热点自带 HTB 队列，硬叠加会打断热点 | App 检测到冲突会**主动拒绝并说明**，不会静默破坏网络 |
| 断网依赖 ebtables | 需在二层按 MAC 拦截 | 无 ebtables 时明确报错，不退化为「按 IP 断网」（那会因换 IP 而失效） |
| 少数机型热点在独立 netns | 厂商实现差异 | 看不到邻居表；已提供诊断命令用于确认 |

**限速是唯一有风险的功能**。它被设计成：只在确认不冲突时执行、任何步骤失败都自动回滚、
并提供「清除全部限速/断网规则」一键还原。断网、统计、计费功能不涉及此类风险。
