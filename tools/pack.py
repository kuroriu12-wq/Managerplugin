#!/usr/bin/env python3
import sys
import zipfile
from pathlib import Path


def main(argv):
    if len(argv) != 3:
        print("usage: pack.py <plugin folder> <output.zip>", file=sys.stderr)
        return 2
    folder = Path(argv[1])
    out = Path(argv[2])
    if not (folder / "manifest.json").is_file():
        print(f"{folder}: manifest.json not found", file=sys.stderr)
        return 1
    out.parent.mkdir(parents=True, exist_ok=True)
    files = sorted(p for p in folder.rglob("*") if p.is_file())
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for path in files:
            z.write(path, path.relative_to(folder).as_posix())
    print(f"{out} ({len(files)} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
