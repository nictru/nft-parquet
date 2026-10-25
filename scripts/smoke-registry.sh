#!/usr/bin/env bash
# Run only after the public registry index and GitHub release asset are available.
set -euo pipefail

if [[ $# != 1 || ! $1 =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: bash scripts/smoke-registry.sh VERSION" >&2
    exit 2
fi
version=$1
nf_test=$(command -v nf-test) || { echo 'nf-test 0.9.5 is required' >&2; exit 1; }
command -v nextflow >/dev/null || { echo 'Nextflow is required' >&2; exit 1; }
temp_root=${TMPDIR:-/nfs/proj/n.trummer/opencode/tmp/opencode}
if [[ ! -d $temp_root ]]; then
    temp_root=/tmp
fi
scratch=$(mktemp -d "$temp_root/nft-parquet-registry.XXXXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
cd "$scratch"

cat > nf-test.config <<EOF
config {
    testsDir 'tests'
    workDir '.nf-test'
    plugins {
        repository 'https://raw.githubusercontent.com/nictru/nft-parquet/main/plugins.json'
        load 'nictru-nft-parquet@$version'
    }
}
EOF
mkdir tests
cat > main.nf <<'EOF'
nextflow.enable.dsl=2
workflow { }
EOF
cat > tests/registry.nf.test <<'EOF'
nextflow_workflow {
    name 'Custom Parquet registry registration'
    script '../main.nf'
    test('Path extension is registered from the resolved plugin') {
        then {
            assert workflow.success
            def local = path('not-read.parquet')
            assert local.metaClass.respondsTo(local, 'parquet')
        }
    }
}
EOF

# No local loadFromFile or direct asset download: nf-test must resolve the ID.
"$nf_test" test tests/registry.nf.test
cached=".nf-test/plugins/nictru-nft-parquet/$version/nictru-nft-parquet-$version.jar"
test -f "$cached"
python3 - "$cached" "$version" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    descriptor = dict(line.split('=', 1) for line in
                      jar.read('META-INF/nf-test-plugin').decode().splitlines()
                      if '=' in line and not line.startswith('#'))
    assert descriptor['moduleName'] == 'nft-parquet', descriptor
    assert descriptor['moduleVersion'] == sys.argv[2], descriptor
print('Fresh-cache registry resolution and plugin registration passed')
PY
