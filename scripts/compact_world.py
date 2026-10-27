"""Explicit offline collection with resumable metadata marking and a bounded sweep. No terrain hashing."""
import argparse, contextlib, json, pathlib, re, sqlite3, time


@contextlib.contextmanager
def exclusion(authority):
    import msvcrt
    with contextlib.ExitStack() as stack:
        members = authority / 'sessions'
        members.mkdir(exist_ok=True)
        for path in [members / 'gate', authority / 'store.lock'] + list(members.glob('*.lock')):
            stream = stack.enter_context(path.open('a+b'))
            stream.seek(0)
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as error:
                raise RuntimeError('Save and quit every linked instance before offline collection') from error
        yield


def compact(authority, max_files=500, seconds=10, metadata_files=100):
    authority = pathlib.Path(authority).resolve()
    deadline = time.monotonic() + seconds
    removed = marked = inspected = 0

    def read(file):
        file.resolve().relative_to(authority)
        if time.monotonic() >= deadline:
            raise TimeoutError('Metadata budget exhausted; resume collection in another invocation')
        if file.stat().st_size > 32 * 1024 * 1024:
            raise ValueError('Metadata file exceeds the collection budget')
        return json.loads(file.read_text(encoding='utf-8'))

    def identity(value):
        if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f-]{36}', value):
            raise ValueError('Invalid immutable terrain index identity')
        return value

    def index_file(value):
        value = identity(value)
        file = authority / 'indexes' / value[:2] / (value + '.json')
        return file if file.is_file() else authority / 'indexes' / (value + '.json')

    with exclusion(authority):
        if (authority / 'pending.json').exists() or (authority / 'restore.json').exists():
            raise RuntimeError('Recover the authority journal before collection')
        state = read(authority / 'world.json')
        if state.get('schema') != 3 or not state.get('terrainRoot') or not state.get('nativeBase'):
            raise ValueError('Save the world with the updated adapter before collecting it')
        roots = {identity(state['terrainRoot'])}
        baseline = authority / 'native-base' / identity(state['nativeBase']) / 'versions'
        for checkpoint_file in (authority / 'saved').glob('*.authority.json'):
            checkpoint = read(checkpoint_file)
            if checkpoint.get('worldId') != state['worldId']:
                raise ValueError('Saved checkpoint belongs to another world')
            if checkpoint.get('nativeBase') != state['nativeBase']:
                raise ValueError('Saved checkpoint has a different native terrain boundary')
            if checkpoint.get('terrainRoot'):
                roots.add(identity(checkpoint['terrainRoot']))
        obsolete_pins = set()
        for pin_file in (authority / 'pins').glob('*.json'):
            pin = read(pin_file)
            snapshot = pathlib.Path(pin['snapshot'])
            sidecar = snapshot.with_name(snapshot.name + '.authority.json')
            if snapshot.is_file() and sidecar.is_file():
                if time.monotonic() >= deadline or sidecar.stat().st_size > 16384:
                    raise TimeoutError('Pinned checkpoint metadata exceeds the current budget')
                checkpoint = json.loads(sidecar.read_text(encoding='utf-8'))
                if checkpoint.get('worldId') != state['worldId']:
                    raise ValueError('Pinned checkpoint belongs to another world')
                if checkpoint.get('nativeBase') != state['nativeBase']:
                    raise ValueError('Pinned checkpoint has a different native terrain boundary')
                roots.add(identity(checkpoint['terrainRoot']))
            else:
                obsolete_pins.add(pin_file.name)
        # Metadata version identity, not a content hash or a scan of the object repository.
        stamp = json.dumps([state['worldId'], state['nativeBase'], state['revision'], state['epoch'], sorted(roots)])
        database = authority / 'gc.sqlite'
        database.resolve().relative_to(authority)
        with sqlite3.connect(database) as db:
            db.execute('PRAGMA cache_size=-2048')
            db.execute('CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT)')
            db.execute('CREATE TABLE IF NOT EXISTS indexes (id TEXT PRIMARY KEY)')
            db.execute('CREATE TABLE IF NOT EXISTS objects (hash TEXT PRIMARY KEY)')
            db.execute('CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, kind TEXT)')

            def get(key, default=''):
                row = db.execute('SELECT value FROM state WHERE key=?', (key,)).fetchone()
                return row[0] if row else default

            def put(key, value):
                db.execute('INSERT OR REPLACE INTO state VALUES (?,?)', (key, str(value)))

            if get('stamp') != stamp:
                for table in ('indexes', 'objects', 'tasks', 'state'):
                    db.execute('DELETE FROM ' + table)
                db.executemany('INSERT INTO tasks VALUES (?,?)', ((root, 'root') for root in roots))
                if baseline.is_dir():
                    db.execute('INSERT INTO tasks VALUES (?,?)', ('base:' + str(baseline.relative_to(authority)), 'basedir'))
                put('stamp', stamp)
                put('phase', 'MARK')
                put('directory', 0)
                put('last', '')
                db.commit()
            if get('phase') == 'DONE':
                put('phase', 'SWEEP')
                put('directory', 0)
                put('last', '')
                db.commit()
            while get('phase') == 'MARK' and marked < metadata_files and time.monotonic() < deadline:
                task = db.execute('SELECT id,kind FROM tasks LIMIT 1').fetchone()
                if task is None:
                    put('phase', 'SWEEP')
                    db.commit()
                    break
                ident, kind = task
                if kind == 'basedir':
                    directory = authority / ident[5:]
                    directory.resolve().relative_to(baseline.resolve())
                    cursor_key = 'cursor:' + ident
                    last_name = get(cursor_key)
                    # Baseline directories are region-partitioned; references are immutable.
                    # Stop and retain the directory task when this invocation exhausts its budget.
                    finished = True
                    for file in sorted(directory.iterdir(), key=lambda file: file.name):
                        if file.name <= last_name:
                            continue
                        if marked >= metadata_files or time.monotonic() >= deadline:
                            finished = False
                            break
                        file.resolve().relative_to(baseline.resolve())
                        if file.is_dir():
                            db.execute('INSERT OR IGNORE INTO tasks VALUES (?,?)', ('base:' + str(file.relative_to(authority)), 'basedir'))
                        elif file.suffix == '.json':
                            ref = read(file).get('ref')
                            if ref is not None:
                                if not re.fullmatch(r'[0-9a-f]{64}', ref['hash']):
                                    raise ValueError('Malformed original native reference')
                                db.execute('INSERT OR IGNORE INTO objects VALUES (?)', (ref['hash'],))
                        put(cursor_key, file.name)
                        marked += 1
                    if finished:
                        db.execute('DELETE FROM tasks WHERE id=?', (ident,))
                        db.execute('DELETE FROM state WHERE key=?', (cursor_key,))
                    db.commit()
                    continue
                value = read(index_file(ident))
                if kind == 'root':
                    if len(value) > 256:
                        raise ValueError('Invalid terrain index root')
                    for shard in value.values():
                        shard = identity(shard)
                        if not db.execute('SELECT 1 FROM indexes WHERE id=?', (shard,)).fetchone():
                            db.execute('INSERT OR IGNORE INTO tasks VALUES (?,?)', (shard, 'shard'))
                else:
                    for key, ref in value.items():
                        if not re.fullmatch(r'-?[0-9]+,-?[0-9]+', key) or not re.fullmatch(r'[0-9a-f]{64}', ref['hash']):
                            raise ValueError('Malformed immutable chunk reference')
                        db.execute('INSERT OR IGNORE INTO objects VALUES (?)', (ref['hash'],))
                db.execute('INSERT OR IGNORE INTO indexes VALUES (?)', (ident,))
                db.execute('DELETE FROM tasks WHERE id=?', (ident,))
                db.commit()
                marked += 1
            # Marking must finish entirely before any object is removed. Active readers and
            # staged writes are excluded by world membership, and pending journals refuse entry.
            directories = [authority / 'indexes'] + [authority / 'indexes' / f'{i:02x}' for i in range(256)]
            directories += [authority / 'objects' / f'{i:02x}' for i in range(256)] + [authority / 'pins']
            cursor = int(get('directory', '0'))
            while get('phase') == 'SWEEP' and cursor < len(directories) and inspected < max_files and time.monotonic() < deadline:
                directory = directories[cursor]
                files = sorted((file for file in directory.iterdir() if file.is_file()), key=lambda file: file.name) if directory.is_dir() else []
                for file in files:
                    if file.name <= get('last'):
                        continue
                    if inspected >= max_files or time.monotonic() >= deadline:
                        break
                    unused = False
                    object_id = file.name.split('.')[0]
                    if directory.parent.name == 'objects' and (file.suffix in ('.gz', '.accepted') or file.name.endswith('.verification.lock')) and re.fullmatch(r'[0-9a-f]{64}', object_id):
                        unused = not db.execute('SELECT 1 FROM objects WHERE hash=?', (object_id,)).fetchone()
                    elif directory.name == 'pins':
                        unused = file.name in obsolete_pins
                    elif file.suffix == '.json' and re.fullmatch(r'[0-9a-f-]{36}', file.stem):
                        unused = not db.execute('SELECT 1 FROM indexes WHERE id=?', (file.stem,)).fetchone()
                    if unused:
                        file.resolve().relative_to(authority)
                        file.unlink()
                        removed += 1
                    put('last', file.name)
                    inspected += 1
                db.commit()
                if inspected >= max_files or time.monotonic() >= deadline:
                    break
                cursor += 1
                put('directory', cursor)
                put('last', '')
                db.commit()
            if cursor == len(directories):
                put('phase', 'DONE')
                db.commit()
            return dict(removed=removed, marked=marked, inspected=inspected, phase=get('phase'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('authority', type=pathlib.Path)
    parser.add_argument('--max-files', type=int, default=500)
    parser.add_argument('--metadata-files', type=int, default=100)
    parser.add_argument('--seconds', type=float, default=10)
    args = parser.parse_args()
    if args.max_files < 1 or args.metadata_files < 1 or args.seconds <= 0:
        parser.error('Budgets must be positive')
    print(json.dumps(compact(args.authority, args.max_files, args.seconds, args.metadata_files)))
