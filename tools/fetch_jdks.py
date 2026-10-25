from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request
import urllib.error
import zipfile


CACHE = Path.home() / ".cache" / "steambridge-build"


def fetch(version):
    destination = CACHE / ("jdk-" + str(version))
    java = destination / "bin" / "java.exe"
    if java.exists():
        return str(destination)
    url = ("https://api.adoptium.net/v3/assets/latest/" + str(version)
           + "/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse")
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            asset = json.load(response)[0]
        package = asset["binary"]["package"]
        vendor_version = asset["version"]["semver"]
    except urllib.error.HTTPError:
        name = "amazon-corretto-" + str(version) + "-x64-windows-jdk.zip"
        with urllib.request.urlopen("https://corretto.aws/downloads/latest_sha256/" + name, timeout=60) as response:
            checksum = response.read().decode().strip().split()[0]
        package = {"name": name, "link": "https://corretto.aws/downloads/latest/" + name,
                   "checksum": checksum}
        vendor_version = "Corretto " + str(version)
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = CACHE / package["name"]
    if not archive.exists():
        print("Downloading JDK", version, flush=True)
        urllib.request.urlretrieve(package["link"], archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != package["checksum"]:
        raise RuntimeError("JDK checksum mismatch: " + str(version))
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            parts = Path(entry.filename).parts[1:]
            if not parts or entry.is_dir():
                continue
            target = destination.joinpath(*parts)
            target.resolve().relative_to(destination.resolve())
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(entry))
    (destination / "download.json").write_text(json.dumps({
        "version": vendor_version, "url": package["link"], "sha256": digest
    }, indent=2) + "\n", encoding="utf-8")
    print("Verified JDK", version, java, flush=True)
    return str(destination)


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=3) as executor:
        for home in executor.map(fetch, (8, 17, 21, 25)):
            print(home, flush=True)
