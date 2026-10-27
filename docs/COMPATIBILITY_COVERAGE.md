# 原版方块状态覆盖报告

由五个版本的真实注册表和目标原生解码器生成。统计单位是导出的状态条目，**不是方块种类或游戏行为兼容率**。

现代版本遍历全部合法状态；旧版本统计去重后的可导出 metadata 表示，数量不能直接横向比较。

| 来源 | 目标 | 状态总数 | 直接支持 | 规则/metadata 转换 | 需要保留 |
|---|---|---:|---:|---:|---:|
| 1.7.10 | 1.7.10 | 2649 | 0 | 2649 | 0 |
| 1.7.10 | 1.12.2 | 2649 | 0 | 1092 | 1557 |
| 1.7.10 | 1.16.5 | 2649 | 1057 | 0 | 1592 |
| 1.7.10 | 1.18.2 | 2649 | 1053 | 4 | 1592 |
| 1.7.10 | 1.20.1 | 2649 | 1053 | 4 | 1592 |
| 1.12.2 | 1.7.10 | 1600 | 0 | 1084 | 516 |
| 1.12.2 | 1.12.2 | 1600 | 0 | 1600 | 0 |
| 1.12.2 | 1.16.5 | 1600 | 1552 | 1 | 47 |
| 1.12.2 | 1.18.2 | 1600 | 1549 | 4 | 47 |
| 1.12.2 | 1.20.1 | 1600 | 1549 | 4 | 47 |
| 1.16.5 | 1.7.10 | 17112 | 0 | 1040 | 16072 |
| 1.16.5 | 1.12.2 | 17112 | 0 | 1529 | 15583 |
| 1.16.5 | 1.16.5 | 17112 | 17112 | 0 | 0 |
| 1.16.5 | 1.18.2 | 17112 | 17107 | 5 | 0 |
| 1.16.5 | 1.20.1 | 17112 | 17107 | 5 | 0 |
| 1.18.2 | 1.7.10 | 20342 | 0 | 1036 | 19306 |
| 1.18.2 | 1.12.2 | 20342 | 0 | 1526 | 18816 |
| 1.18.2 | 1.16.5 | 20342 | 17062 | 50 | 3230 |
| 1.18.2 | 1.18.2 | 20342 | 20342 | 0 | 0 |
| 1.18.2 | 1.20.1 | 20342 | 20342 | 0 | 0 |
| 1.20.1 | 1.7.10 | 24135 | 0 | 1036 | 23099 |
| 1.20.1 | 1.12.2 | 24135 | 0 | 1526 | 22609 |
| 1.20.1 | 1.16.5 | 24135 | 16978 | 134 | 7023 |
| 1.20.1 | 1.18.2 | 24135 | 20230 | 84 | 3821 |
| 1.20.1 | 1.20.1 | 24135 | 24135 | 0 | 0 |

完整状态明细位于 `build/compatibility/audit-*-to-*.json`；按方块汇总的缺口见 `build/compatibility/summary.json` 的 `gaps`。

## 1.20.1 → 1.16.5 的主要保留项

| 方块 | 原因 | 状态数 |
|---|---|---:|
| minecraft:calibrated_sculk_sensor | missing_block | 384 |
| minecraft:note_block | unsupported_value | 350 |
| minecraft:cobbled_deepslate_wall | missing_block | 324 |
| minecraft:deepslate_brick_wall | missing_block | 324 |
| minecraft:deepslate_tile_wall | missing_block | 324 |
| minecraft:mud_brick_wall | missing_block | 324 |
| minecraft:polished_deepslate_wall | missing_block | 324 |
| minecraft:chiseled_bookshelf | missing_block | 256 |
| minecraft:glow_lichen | missing_block | 128 |
| minecraft:sculk_vein | missing_block | 128 |
| minecraft:sculk_sensor | missing_block | 96 |
| minecraft:bamboo_mosaic_stairs | missing_block | 80 |
| minecraft:bamboo_stairs | missing_block | 80 |
| minecraft:cherry_stairs | missing_block | 80 |
| minecraft:cobbled_deepslate_stairs | missing_block | 80 |
| minecraft:cut_copper_stairs | missing_block | 80 |
| minecraft:deepslate_brick_stairs | missing_block | 80 |
| minecraft:deepslate_tile_stairs | missing_block | 80 |
| minecraft:exposed_cut_copper_stairs | missing_block | 80 |
| minecraft:mangrove_stairs | missing_block | 80 |
| minecraft:mud_brick_stairs | missing_block | 80 |
| minecraft:oxidized_cut_copper_stairs | missing_block | 80 |
| minecraft:polished_deepslate_stairs | missing_block | 80 |
| minecraft:waxed_cut_copper_stairs | missing_block | 80 |
| minecraft:waxed_exposed_cut_copper_stairs | missing_block | 80 |
