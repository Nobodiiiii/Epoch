"""Summarize native adapter audits. Never infer support from an ID-only mapping."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

VERSIONS = ('1.7.10', '1.12.2', '1.16.5', '1.18.2', '1.20.1')
ROOT = Path(__file__).resolve().parents[1]


def summarize(directory):
    catalogs = {version: json.loads((directory / f'catalog-{version}.json').read_text(encoding='utf-8'))
                for version in VERSIONS}
    rows, gaps = [], []
    for source in VERSIONS:
        for target in VERSIONS:
            path = directory / f'audit-{source}-to-{target}.json'
            audit = json.loads(path.read_text(encoding='utf-8'))
            if (audit['sourceVersion'], audit['targetVersion']) != (source, target):
                raise ValueError(f'Wrong audit identity: {path}')
            if audit['totalStates'] != len(catalogs[source]['states']):
                raise ValueError(f'Stale/incomplete catalog audit: {path}')
            if [(s['block'], s['snbt']) for s in audit['states']] != [(s['block'], s['snbt']) for s in catalogs[source]['states']]:
                raise ValueError(f'Audit does not match current source catalog: {path}')
            totals = Counter(s['result']['level'] for s in audit['states'])
            if dict(totals) != audit['stateTotals'] or sum(totals.values()) != audit['totalStates']:
                raise ValueError(f'Incomplete state classification: {path}')
            if set(totals) - {'EXACT', 'CONVERTED', 'PRESERVED'}:
                raise ValueError(f'Unsupported classification: {path}')
            if source == target and totals.get('PRESERVED', 0):
                raise ValueError(f'Same-version decoder regression: {path}')
            rows.append(dict(source=source, target=target, total=audit['totalStates'],
                             exact=totals.get('EXACT', 0), converted=totals.get('CONVERTED', 0),
                             preserved=totals.get('PRESERVED', 0), rulesSha256=audit['rulesSha256'],
                             auditSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                             payloadStatesNotTested=audit['blockEntityStatesNotPayloadTested']))
            affected = Counter((s['block'], s['result'].get('reason', 'unknown'))
                               for s in audit['states'] if s['result']['level'] == 'PRESERVED')
            gaps.extend(dict(source=source, target=target, block=block, reason=reason, states=count)
                        for (block, reason), count in sorted(affected.items()))
    # All modern target runs must have used the same pack snapshot.
    hashes = {r['rulesSha256'] for r in rows if r['target'] in VERSIONS[2:]}
    if len(hashes) != 1:
        raise ValueError('Modern audits used different compatibility packs; rerun all targets')
    summary = dict(schema=1, scope='native state decoding; world behavior and payloads unverified',
                   rows=rows, gaps=gaps)
    (directory / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    text = ['# 原版方块状态覆盖报告', '',
            '由五个版本的真实注册表和目标原生解码器生成。统计单位是导出的状态条目，**不是方块种类或游戏行为兼容率**。', '',
            '1.12 及现代版本遍历全部注册状态（含需邻居推导的组合）；1.7 保留原始 metadata 枚举及来源，不代表全部可达。数量不能直接横向比较。', '',
            '“保留”表示使用未知方块保存数据；NBT、容器内容、多方块结构及邻居行为未在本报告中测试。', '',
            '| 来源 | 目标 | 状态总数 | 直接支持 | 规则/metadata 转换 | 需要保留 |',
            '|---|---|---:|---:|---:|---:|']
    for row in rows:
        text.append('| {source} | {target} | {total} | {exact} | {converted} | {preserved} |'.format(**row))
    text += ['', '完整状态明细见同目录 `audit-*-to-*.json`；按方块汇总的缺口见 `summary.json` 的 `gaps`。',
             '', '## 1.20.1 → 1.16.5 的主要保留项', '', '| 方块 | 原因 | 状态数 |', '|---|---|---:|']
    selected = sorted((g for g in gaps if g['source'] == '1.20.1' and g['target'] == '1.16.5'), key=lambda g: (-g['states'], g['block']))
    for gap in selected[:25]:
        text.append('| {block} | {reason} | {states} |'.format(**gap))
    (directory / 'REPORT.md').write_text('\n'.join(text) + '\n', encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / 'build/compatibility')
    args = parser.parse_args()
    result = summarize(args.directory)
    print(f'Verified {len(result["rows"])} version pairs; report: {args.directory / "REPORT.md"}')
