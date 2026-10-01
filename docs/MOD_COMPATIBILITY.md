# 为模组编写方块兼容规则

本接口目前接入 1.16.5、1.18.2、1.20.1 的状态导入器。1.7.10、1.12.2
继续使用原生 metadata 映射；它们已有注册表导出与审计接口，但尚不执行这个
JSON 规则包。需要复杂机器 NBT 转换的内容仍由未知方块保存。

## 安装、检查与生效

1. 复制 `examples/compatibility/example-mod.json`，替换示例模组 ID 和版本。
   示例不会自动安装，不能把示例版本号当成实际兼容承诺。
2. 将 JSON 放到项目 `.minecraft/epoch/compatibility/packs/`。
3. 每次世界导入前重新读取整个目录。无需重新编译 Epoch；同一次导入使用
   不可变规则快照。Java provider 的变化仍需要重启实例。
4. 在状态文件的 `diagnostics.compatibilityRules` 查看规则 ID 和 SHA-256；
   在 `blockStateCompatibility` 查看状态失败原因，`blockEntityCompatibility`
   查看方块实体失败原因。

可用 JVM 参数 `-Depoch.compatibilityDir=绝对路径` 改变规则目录。缺少目录表示
只有内置规则；损坏的 JSON、重复规则 ID 或非法配置会在修改目标世界之前
中止导入，错误消息包含文件名。不会悄悄丢弃一个包继续执行。

## JSON 规则结构

根对象只包含 `schema: 1` 和 `rules` 数组。一条规则例如：

```json
{
  "schema": 1,
  "rules": [{
    "id": "mytrees:dry_maple_leaves",
    "safety": "equivalent",
    "priority": 0,
    "targetVersions": ["1.16.5"],
    "targetMods": {"mytrees": ["1.3.0"]},
    "blocks": ["mytrees:maple_leaves"],
    "missingTargetProperties": ["waterlogged"],
    "drop": {"waterlogged": "false"}
  }]
}
```

只有确认该模组的旧版非含水树叶与新版非含水状态语义等价时，才可使用此规则。
引擎不会猜测任何模组后缀为 `_leaves` 的方块都与原版树叶相同。

| 字段 | 作用 |
|---|---|
| `id` | 唯一命名空间 ID；重复即配置错误 |
| `safety` | 当前必须为 `equivalent`；这是作者的语义等价承诺 |
| `priority` | 可选整数，默认 0；高优先级匹配规则胜出 |
| `targetVersions` | 必填，精确 Minecraft 版本列表，无通配符 |
| `blocks` | 必填，精确输入方块 ID 列表，无通配符 |
| `targetMods` | 可选，目标加载器报告的模组 ID → 精确版本列表；全部满足才匹配 |
| `when` | 可选，输入属性必须具有这些字符串值 |
| `missingTargetProperties` | 可选，原方块在目标注册表中不能包含这些属性 |
| `toBlock` | 可选，输出方块 ID，供改名迁移使用 |
| `drop` | 可选，属性 → 允许删除的精确输入值；值不符则不匹配 |
| `rename` | 可选，旧属性名 → 新属性名；缺少旧属性或新属性已存在则拒绝 |
| `values` | 可选，输出属性名 → 旧值到新值的完整映射；未映射的值拒绝 |
| `add` | 可选，仅补不存在的属性；覆盖已有属性拒绝 |

处理顺序：先尝试直接表示 → 选择一条匹配规则 → `drop` → `rename` → `values`
→ `add` → 使用输出方块注册表校验所有属性。不同 rename 项不能依赖执行顺序，
不要用属性改名链表达交换操作。输出属性未在规则中触及的部分原样保留并校验。

同最高优先级的两条规则同时匹配时，该状态保留为未知方块，原因为
`rule_conflict`。文件名排序不会决定结果。规则不链式执行；这样可以避免包的
加载顺序、循环和两条“各自安全”规则组合后意外丢失数据。

已能直接表示的状态不执行规则，避免把已经使用旧格式的数据再转换一次。
属性值必须写成字符串：`"false"`、`"1"`，不能写成 JSON 布尔或数字。

规则不接受 `sourceVersion` 条件：经过未知方块中转后，当前导出客户端并不
等于原始内容的创建版本。当前通过输入形状及目标版本约束兼容；缺少来源证据
时不会猜测原始模组版本。

## Java provider

编译时依赖对应版本的 Epoch JAR（compile-only），不要把 `dev.epoch.bridge`
复制或打包进自己的模组。provider 构造函数不得访问尚未初始化的世界。

```java
package mytrees.compat;

import dev.epoch.bridge.BlockCompatibilityProvider;
import dev.epoch.bridge.CompatibilityRules;
import java.util.*;

public final class TreeRules implements BlockCompatibilityProvider {
    public List<CompatibilityRules.Rule> rules() {
        CompatibilityRules.Rule rule = new CompatibilityRules.Rule();
        rule.id = "mytrees:dry_maple_leaves";
        rule.safety = "equivalent";
        rule.targetVersions.add("1.16.5");
        rule.blocks.add("mytrees:maple_leaves");
        rule.targetMods.put("mytrees", Arrays.asList("1.3.0"));
        rule.missingTargetProperties.add("waterlogged");
        rule.drop.put("waterlogged", "false");
        return Collections.singletonList(rule);
    }
}
```

在模组资源中增加文件：

```
META-INF/services/dev.epoch.bridge.BlockCompatibilityProvider
```

内容是一行 `mytrees.compat.TreeRules`。Java provider 与 JSON 规则经过同一套
校验、复制和冲突检测，可以由代码生成大量规则，但不能绕过目标注册表或 NBT
保护。这不是执行 JSON 脚本，也不是开放任意类名的反射加载入口。

## 模组作者的验证流程

从两个实际加载该模组的实例分别导出注册表。通过该实例自己的控制目录投递：

```json
{"id":"registry-1","action":"registry","path":"D:/EpochChecks/mod-registry.json"}
```

在目标实例投递：

```json
{"id":"audit-1","action":"auditstates","path":"D:/EpochChecks/mod-registry.json"}
```

结果写入 `mod-registry.json.audit-目标版本.json`。两个操作都不放置方块、不改
存档；需要实例已经进入单人世界，让原有控制通道处理命令。

对每个有规则的状态验证 A→B→A，并检查所有未匹配值、未知属性、错误模组
版本和规则冲突。涉及库存、能量、流体、网络节点或多方块机器的，还必须用
真实世界 fixture 检查 NBT 和行为。注册表审计不能代替这些测试。

离线原版矩阵可运行 `scripts/audit_compatibility.ps1 -Offline`，结果位于
`build/compatibility/REPORT.md` 和完整 JSON。该测试只 bootstrap 原版注册表，
不能据此声称任意第三方模组已通过验证。
