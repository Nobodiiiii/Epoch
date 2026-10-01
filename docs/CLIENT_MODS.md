# 客户端辅助模组安装记录

核查与安装日期：2026-10-01。安装目录为 `D:\Files\Epoch\.minecraft\versions\<版本>-Forge\mods`。

按当前 Forge、Java 和拼音搜索的组合兼容性选取发布；部分作者标记为 Beta 的最新兼容发布也包含在内。没有修改上游 JAR。

| Minecraft | JEI | Jade | Mouse Tweaks（鼠标手势） | JustEnoughCharacters（拼音搜索） | IMBlocker（输入法控制） |
|---|---|---|---|---|---|
| 1.7.10 | 无对应发布 | 无对应发布 | 2.4.4 | 无对应发布 | 无对应发布 |
| 1.12.2 | 4.16.5.1029 | 0.1.0 | 2.10.1（Beta） | 3.7.3 | 无对应发布 |
| 1.16.5 | 7.8.1.1019（Beta） | 2.8.3 | 2.14 | 4.5.6 | 5.6.2 |
| 1.18.2 | 10.2.1.1010（Beta） | 5.3.2 | 2.22 | 4.5.9 | 5.6.2 |
| 1.20.1 | 15.62.0.217（Beta） | 11.13.3 | 2.25.1 | 4.6.11 | 5.6.2 |

## 必需前置

- 1.12.2：Hwyla `1.8.26-B41`。这个版本的 Jade 是 Hwyla 附加组件。
- 1.20.1：MezzConfig `0.6.6`（Beta），满足 JEI 的 `[0.6.3,1.0.0)` 要求。
- 合计安装 22 个文件。Epoch 原有模组保持不变。

## 为什么部分文件没有采用版本号最高的发布

- **1.12.2 JEI**：1030–1035 六个较新发布都混入 Java 21 的 `java/mezz/jei/config/Tags.class`，未通过当前 Java 8 的完整包字节码检查。选用全部 384 个类均为 Java 8 字节码的 `4.16.5.1029`；主类及 Forge 版本要求也匹配现有实例。
- **1.18.2 JEI**：现有最新 JEC `4.5.9` 依赖的普通及低内存搜索接口在较新 JEI 中发生迁移。检查了全部 13 个更新的 Forge 发布后，选用接口仍匹配的 `10.2.1.1010`。
- **1.16.5 JEC**：`4.6.10`、`4.5.14`、`4.5.10` 的 JEI 转换脚本引用未定义变量。选用之前的 `4.5.6`；已直接执行其原版 ASM 转换器处理 JEI `7.8.1.1019` 的搜索类，三个变更方法通过离线字节码验证。

没有对应原项目发布的组合已跳过，没有用 NEI、Waila 或 InputFix 等其他项目替代所请求的项目。1.12.2 的 Hwyla 是 Jade 的必需前置。

## 校验与生效

下载来自 Modrinth 或 Forge CDN。检查了文件长度、ZIP 完整性、Java 字节码版本及依赖；提供发布者哈希的文件也核对了哈希。所有安装文件记录了本地 SHA-256，安装后的哈希与下载文件一致。

**正在运行的实例需要重启游戏才能加载新增模组。** 本轮未关闭正在运行的客户端，也未进行安装后的游戏启动或 Epoch 跨版本切换实测；离线接口与字节码检查不等于游戏内验证。

下载来源、精确文件名、哈希及安装路径见 `.minecraft/epoch/mod-install/installed-manifest.json`。候选版本和兼容性研究记录保留在同目录中。

## 项目来源

- [JEI](https://modrinth.com/mod/jei)
- [Jade](https://modrinth.com/mod/jade)
- [Mouse Tweaks](https://modrinth.com/mod/mouse-tweaks)
- [JustEnoughCharacters](https://modrinth.com/mod/justenoughcharacters)
- [IMBlocker](https://modrinth.com/mod/imblocker-original)
- [Hwyla](https://modrinth.com/mod/hwyla)
- [MezzConfig](https://modrinth.com/mod/7tEfOcA7)
- [JEI 1.18.2 文件 8419049](https://www.curseforge.com/minecraft/mc-mods/jei/files/8419049)
- [JEC 1.16.5 文件 5053975](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters/files/5053975)
- [JEC 1.18.2 文件 5114328](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters/files/5114328)
- [Jade 1.12.2 文件 2735905](https://www.curseforge.com/minecraft/mc-mods/jade/files/2735905)
- [Mouse Tweaks 1.7.10 文件 2209265](https://www.curseforge.com/minecraft/mc-mods/mouse-tweaks/files/2209265)
- [Mouse Tweaks 1.12.2 文件 3359843](https://www.curseforge.com/minecraft/mc-mods/mouse-tweaks/files/3359843)
