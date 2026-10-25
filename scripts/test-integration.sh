#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

if command -v nf-test >/dev/null 2>&1; then
    nf_test=$(command -v nf-test)
elif [[ -f "$repo_root/nf-test/nf-test.jar" || -f "$HOME/.nf-test/nf-test.jar" ]]; then
    nf_test="$repo_root/nf-test/nf-test"
else
    echo 'nf-test is required: install nf-test 0.9.5+ on PATH or its JAR beside nf-test/nf-test.' >&2
    exit 1
fi
if ! command -v nextflow >/dev/null 2>&1; then
    echo 'Nextflow is required on PATH.' >&2
    exit 1
fi

cd "$repo_root"
./gradlew generateIntegrationFixtures pluginJar

if [[ -z ${NFT_PARQUET_JAR:-} ]]; then
    version=$(sed -n 's/^version=//p' gradle.properties)
    export NFT_PARQUET_JAR="$repo_root/build/libs/nft-parquet-${version}.jar"
else
    export NFT_PARQUET_JAR
    NFT_PARQUET_JAR=$(realpath -- "$NFT_PARQUET_JAR")
fi
if [[ ! -f "$NFT_PARQUET_JAR" ]]; then
    echo "Plugin JAR not found: $NFT_PARQUET_JAR" >&2
    exit 1
fi
export NFT_PARQUET_FIXTURES="$repo_root/build/integration-fixtures"

cd "$repo_root/nf-test"
exec "$nf_test" test "$@"
