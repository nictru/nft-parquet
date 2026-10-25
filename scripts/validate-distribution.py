#!/usr/bin/env python3
"""Validate the local release distribution without network or third-party modules."""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def properties(text):
    return dict(
        line.split("=", 1)
        for raw in text.splitlines()
        if (line := raw.strip()) and not line.startswith(("#", "!")) and "=" in line
    )


def validate(root, jar_path=None):
    version = properties((root / "gradle.properties").read_text())["version"]
    require(re.fullmatch(r"\d+\.\d+\.\d+", version), "Expected a release version")
    registry = json.loads((root / "plugins.json").read_text())
    require(isinstance(registry, list), "Registry must be a JSON list")
    ids = [entry["id"] for entry in registry]
    require(len(ids) == len(set(ids)), "Duplicate registry IDs")
    require("nft-parquet" not in ids, "Do not shadow the official registry ID")
    plugin = next(entry for entry in registry if entry["id"] == "nictru-nft-parquet")
    require(plugin["latest"] == version, "Registry latest differs from Gradle version")
    require(plugin["github"] == "nictru/nft-parquet", "Unexpected registry repository")
    releases = plugin["releases"]
    versions = [release["version"] for release in releases]
    require(len(versions) == len(set(versions)), "Duplicate registry versions")
    require(version in versions, "Current version missing from registry")
    for release in releases:
        v = release["version"]
        expected = f"https://github.com/nictru/nft-parquet/releases/download/v{v}/nft-parquet-{v}.jar"
        require(release["url"] == expected, f"Unexpected release URL: {release['url']}")

    jar_path = jar_path or root / "build" / "libs" / f"nft-parquet-{version}.jar"
    require(jar_path.name == f"nft-parquet-{version}.jar", "Wrong distribution filename")
    with zipfile.ZipFile(jar_path) as jar:
        names = jar.namelist()
        classes = [name for name in names if name.endswith(".class")]
        duplicates = [name for name, count in Counter(classes).items() if count > 1]
        require(not duplicates, f"Duplicate classes: {duplicates[:10]}")
        require(names.count("META-INF/nf-test-plugin") == 1, "Missing/duplicate plugin descriptor")
        descriptor = properties(jar.read("META-INF/nf-test-plugin").decode())
        require(descriptor["moduleName"] == "nft-parquet", "Incorrect moduleName")
        require(descriptor["moduleVersion"] == version, "Descriptor version differs from Gradle")
        for key in ("extensionClasses", "extensionMethods"):
            require(descriptor.get(key), f"Missing {key}")
            for cls in descriptor[key].split(","):
                require(cls.strip().replace(".", "/") + ".class" in classes,
                        f"Missing extension implementation: {cls}")
        host_prefixes = ("groovy/", "org/codehaus/groovy/", "org/apache/groovy/",
                         "nextflow/", "com/askimed/nf/test/")
        require(not any(name.startswith(host_prefixes) for name in classes),
                "Host Groovy, Nextflow, or nf-test classes must not be bundled")
        for prefix in ("com/jerolba/carpet/", "org/apache/parquet/"):
            require(any(name.startswith(prefix) for name in classes), f"Missing bundled {prefix}")
        require(not any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I)
                        for name in names), "Stale dependency signatures")
        services = [name for name in names if name.startswith("META-INF/services/") and not name.endswith("/")]
        require(services, "No service descriptors bundled")
        for name in services:
            require(names.count(name) == 1, f"Unmerged service descriptor: {name}")
            providers = [line.split("#", 1)[0].strip()
                         for line in jar.read(name).decode().splitlines()]
            for provider in filter(None, providers):
                require(re.fullmatch(r"[\w$]+(?:\.[\w$]+)+", provider),
                        f"Malformed provider in {name}: {provider}")
                require(provider.replace(".", "/") + ".class" in classes,
                        f"Missing service provider: {provider}")
    print(f"Validated {jar_path} (version {version})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, help="Override the bundled JAR path")
    args = parser.parse_args()
    try:
        validate(Path(__file__).resolve().parents[1], args.jar)
    except (OSError, ValueError, KeyError, StopIteration, zipfile.BadZipFile) as error:
        print(f"Distribution validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
