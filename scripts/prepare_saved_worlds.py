"""Expose one saved Epoch world in each supported local profile without replacing saves."""
import argparse
import json
from pathlib import Path

from epoch_instances import discover, inside
from prepare_transfer import prepare


def prepare_all(root, binding_file, username='EpochPlayer', player_uuid=None):
    root = Path(root).resolve()
    binding_file = Path(binding_file).resolve()
    binding = json.loads(binding_file.read_text(encoding='utf-8'))
    source = Path(binding['sourceWorld']).resolve()
    if not inside(source, root / '.minecraft') or not inside(binding['authority'], root / '.minecraft'):
        raise ValueError('World binding must stay in this project')
    name = source.name + ' [Epoch ' + binding['worldId'][:8] + ']'
    results = []
    seed = dict(instanceId='', version='', gameDir=str(root), sessionId='saved-worlds')
    for entry in discover(root, seed)['instances']:
        if not entry['supported']:
            continue
        game = Path(entry['gameDir'])
        linked = None
        for candidate in (game / 'saves').glob('*/epoch/authority.json'):
            try:
                if json.loads(candidate.read_text(encoding='utf-8'))['worldId'] == binding['worldId']:
                    linked = candidate.parent.parent
                    break
            except (OSError, ValueError, KeyError):
                continue
        if linked is None:
            linked = game / 'saves' / name
            # prepare refuses unrelated saves, so a collision never replaces user data.
            prepare(entry['version'], game, name, username, player_uuid, binding_file)
        results.append(dict(version=entry['version'], world=str(linked)))
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('binding')
    parser.add_argument('--root', default=str(Path(__file__).resolve().parents[1]))
    args = parser.parse_args()
    print(json.dumps(prepare_all(args.root, args.binding), ensure_ascii=False))
