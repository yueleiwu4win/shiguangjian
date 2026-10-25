"""Download an official tool archive in verified HTTP ranges. No installation outside this workspace."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import Request, urlopen
import argparse
import time

def main():
    p = argparse.ArgumentParser()
    p.add_argument("url")
    p.add_argument("output")
    args = p.parse_args()
    target = Path(args.output).resolve()
    parts_dir = target.with_suffix(target.suffix + ".parts")
    parts_dir.mkdir(parents=True, exist_ok=True)
    with urlopen(Request(args.url, headers={"Range": "bytes=0-0", "User-Agent": "Daybook-build/1"}), timeout=60) as r:
        content_range = r.headers.get("Content-Range", "")
        if not content_range.startswith("bytes 0-0/"):
            raise RuntimeError("Server does not support validated range requests")
        total = int(content_range.split("/")[-1])
        final_url = r.url
    count = 16
    size = (total + count - 1) // count
    def download(i):
        start, end = i * size, min((i + 1) * size, total) - 1
        part = parts_dir / str(i)
        if part.exists() and part.stat().st_size == end - start + 1:
            return
        for attempt in range(4):
            try:
                with urlopen(Request(final_url, headers={"Range": f"bytes={start}-{end}", "User-Agent": "Daybook-build/1"}), timeout=120) as r:
                    if r.headers.get("Content-Range") != f"bytes {start}-{end}/{total}":
                        raise RuntimeError("Unexpected range response")
                    with part.open("wb") as f:
                        while chunk := r.read(128 * 1024):
                            f.write(chunk)
                if part.stat().st_size != end - start + 1:
                    raise RuntimeError("Truncated download")
                print(f"Part {i + 1}/{count} complete", flush=True)
                return
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(2)
    with ThreadPoolExecutor(max_workers=count) as pool:
        list(pool.map(download, range(count)))
    with target.open("wb") as out:
        for i in range(count):
            with (parts_dir / str(i)).open("rb") as src:
                while chunk := src.read(1024 * 1024):
                    out.write(chunk)
    print(f"Downloaded {total} bytes to {target}", flush=True)

if __name__ == "__main__":
    main()
