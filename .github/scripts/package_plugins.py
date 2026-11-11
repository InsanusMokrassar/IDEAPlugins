"""Wrap the three installable plugin ZIPs in one reproducible release bundle."""

import io
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


PLUGIN_IDS = {
    "dev.inmo.idea.koin.dependency.navigation",
    "dev.inmo.idea.navigation.flow.diagram",
    "dev.inmo.idea.function.call.graph",
}


def plugin_id(content):
    descriptors = []
    with zipfile.ZipFile(io.BytesIO(content)) as plugin:
        for entry in plugin.infolist():
            if entry.filename.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(plugin.read(entry))) as jar:
                    if "META-INF/plugin.xml" in jar.namelist():
                        descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
    if len(descriptors) != 1:
        raise ValueError("Expected one plugin descriptor in each installable ZIP.")
    return descriptors[0].findtext("id")


def package_plugins(root, repository):
    if not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Expected GITHUB_REPOSITORY in owner/name format.")
    name = repository.rsplit("/", 1)[-1]
    if name in {".", ".."}:
        raise ValueError("Invalid repository name.")
    versions = re.findall(
        r"^[ \t]*bundleVersion[ \t]*=[ \t]*(\S+)[ \t]*$",
        (root / "gradle.properties").read_text(encoding="utf-8"), re.MULTILINE,
    )
    if len(versions) != 1 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9._+-]+)?", versions[0]):
        raise ValueError("Expected one valid bundleVersion in gradle.properties.")
    version = versions[0]
    inputs = sorted((root / "local.dists").glob("*.zip"))
    if len(inputs) != len(PLUGIN_IDS):
        raise ValueError(f"Expected exactly three plugin ZIPs in local.dists, found {len(inputs)}.")
    contents = {}
    for path in inputs:
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Plugin ZIP must be a regular file: {path.name}")
        contents[path.name] = path.read_bytes()
    if {plugin_id(content) for content in contents.values()} != PLUGIN_IDS:
        raise ValueError("Expected one ZIP for each plugin in the bundle.")

    relative = Path("build/distributions") / f"{name}-{version}.zip"
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as bundle:
        for filename, content in contents.items():
            # Fixed metadata makes retries independent of build times and filesystem modes.
            entry = zipfile.ZipInfo(filename, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, content)
    return {
        "version": version, "tag": f"{name}-v{version}",
        "zip": relative.as_posix(), "name": relative.name,
    }


def main():
    root = Path(__file__).resolve().parents[2]
    metadata = package_plugins(root, os.environ["GITHUB_REPOSITORY"])
    if output_path := os.environ.get("GITHUB_OUTPUT"):
        with open(output_path, "a", encoding="utf-8") as output:
            for key, value in metadata.items():
                output.write(f"{key}={value}\n")
    print(f"Created {metadata['zip']}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, ET.ParseError) as error:
        raise SystemExit(f"::error::{error}") from error
