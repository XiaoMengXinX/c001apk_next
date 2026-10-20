# c001apk_next

fake coolapk

[![装机量](https://service.houlangs.cn/c001apk/stats.php?format=svg&metric=installs)](https://service.houlangs.cn/c001apk/stats.php)
[![今日活跃](https://service.houlangs.cn/c001apk/stats.php?format=svg&metric=active)](https://service.houlangs.cn/c001apk/stats.php)
[![今日新增](https://service.houlangs.cn/c001apk/stats.php?format=svg&metric=new)](https://service.houlangs.cn/c001apk/stats.php)
[![7天内活跃](https://service.houlangs.cn/c001apk/stats.php?format=svg&metric=active7)](https://service.houlangs.cn/c001apk/stats.php)

![装机量历史](https://service.houlangs.cn/c001apk/stats.php?format=svg&metric=chart)

## 下载 / 更新

最新正式版：**[release-latest](https://github.com/XiaoMengXinX/c001apk_next/releases/tag/release-latest)**（正式版与测试版共用同一 versionCode，可互相覆盖安装）

App 内「设置 → 关于」可检查更新：直接读取本仓库的 GitHub Releases，有新版本时跳转浏览器下载。

上面的徽章与曲线来自[自建统计页](https://service.houlangs.cn/c001apk/stats.php)，30 分钟刷新一次。

统计按**匿名设备标识**计，与账号无关：

- 安卓 8.0+ 用系统派发给本应用的标识（`Settings.Secure.ANDROID_ID`）——**清数据、卸载重装都不会变**，因此同一台设备不会被重复计数；别的应用拿到的值不同，不构成跨应用关联。
- 桌面端（Windows）没有对应能力，用本机首启生成的随机标识。
- 超过 60 天没有请求的设备视为已卸载，从装机量里扣除；**请求量不作为指标**。

## 本 fork 的改动

本仓库 fork 自 [kongwufang/c001apk_next](https://github.com/kongwufang/c001apk_next)，
主要改动是**把酷安 API 的请求身份从 Android 换成 iOS**。

上游用的是 Android 客户端那套签名与设备指纹（v3 token + 数字联盟 `szlmId`），
本 fork 换成 iOS 客户端的实现：请求头、设备串、token 算法、User-Agent 全部按 iOS 客户端生成，
设备机型档案在首次启动时随机选定后持久化，多次启动之间保持一致。

由于随机生成的设备 ID 只能调用读接口，点赞、回复等写操作会被风控拦下，
所以登录后会向 CDID 服务申请一个服务端登记过的设备 ID 并长期复用；
退出登录时清除，回到随机 ID。「设置 → 高级 → 参数」里可以查看当前设备 ID、
调整机型参数或重新生成。

## 关于本项目 / Origin

本项目基于上游两个开源项目改编，沿用 **GNU Affero General Public License v3.0 (AGPL-3.0)** 发布，详见 [`LICENSE.md`](./LICENSE.md)。

代码主干血统（lineage）：

```
bggRGjQaUbCoE/c001apk-flutter  (Flutter 重写，已归档)
        │
        ▼
RzdPai/c001apk                (Kotlin 重写，本项目代码主线)
        │
        ▼
kongwufang/c001apk  ──fork──>  kongwufang/c001apk_next  ──fork──>  XiaoMengXinX/c001apk_next  (本仓库)
```

- 上游 Kotlin 主线：[RzdPai/c001apk](https://github.com/RzdPai/c001apk)
- 上游 Flutter 重写（已归档）：[bggRGjQaUbCoE/c001apk-flutter](https://github.com/bggRGjQaUbCoE/c001apk-flutter)
- 直接上游：[kongwufang/c001apk_next](https://github.com/kongwufang/c001apk_next)

如上游更新，本项目会视情况进行同步（rebase/merge）。所有上游版权与许可声明均完整保留在 `LICENSE.md`。

---

Token/LoginUtil from: [CoolbbsYou](https://github.com/WaitFme/CoolbbsYou)
