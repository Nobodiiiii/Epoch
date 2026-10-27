"""Resolve JDKs once during preparation, with portable local configuration."""
import argparse, functools, json, os, pathlib, re, shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]

@functools.lru_cache(maxsize=None)
def java_home(major):
    config_file = pathlib.Path(os.environ.get('EPOCH_RUNTIME_CONFIG', ROOT / 'epoch-runtime.json'))
    config = json.loads(config_file.read_text(encoding='utf-8')) if config_file.exists() else {}
    explicit = os.environ.get(f'EPOCH_JAVA{major}_HOME') or config.get(f'java{major}Home')
    candidates = [explicit] if explicit else []
    if not explicit:
        candidates += [os.environ.get('JAVA_HOME')]
        executable = shutil.which('javac')
        if executable:
            candidates.append(str(pathlib.Path(executable).resolve().parent.parent))
        for directory in (pathlib.Path.home() / '.jdks', pathlib.Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Java',
                          pathlib.Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Eclipse Adoptium',
                          pathlib.Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Microsoft'):
            if directory.is_dir():
                candidates += [str(path) for path in sorted(directory.iterdir()) if path.is_dir()]
    for candidate in dict.fromkeys(candidates):
        if not candidate:
            continue
        home = pathlib.Path(candidate).expanduser().resolve()
        release = home / 'release'
        if not release.is_file() or not (home / 'bin/javac.exe').is_file():
            continue
        match = re.search(r'JAVA_VERSION="(?:1\.)?(\d+)', release.read_text(encoding='utf-8'))
        if match and int(match[1]) == major:
            return home
    raise RuntimeError(f'Java {major} JDK not found. Set EPOCH_JAVA{major}_HOME or java{major}Home in {config_file}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=int, choices=(8, 17), required=True)
    print(java_home(parser.parse_args().java_home))
