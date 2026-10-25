# nft-parquet

Local Parquet assertions for [nf-test](https://www.nf-test.com/), implemented directly with Carpet and Apache Parquet (not a delegation to `nf-parquet`).

Install **0.1.0** from the GitHub-hosted custom registry below, or build the plugin locally.

## Install

Requires Java 17 or newer, nf-test 0.9.5, and Nextflow. CI targets Java 17 and 21. Groovy is supplied by nf-test, not bundled in this plugin.

### Install from GitHub (recommended)

No official nf-test registry registration, cloning, or local build is needed. Add the GitHub-hosted registry to your pipeline's `nf-test.config`:

```groovy
config {
    plugins {
        repository 'https://raw.githubusercontent.com/nictru/nft-parquet/main/plugins.json'
        load 'nictru-nft-parquet@0.1.0'
    }
}
```

Then run `nf-test test` as usual. nf-test downloads the bundled plugin JAR directly from this fork's GitHub release and caches it locally. Keep the version pinned to make pipeline tests reproducible.

The unique **registry ID** `nictru-nft-parquet` is intentional. nf-test 0.9.5 searches the official registry first; the original `nft-parquet` ID resolves there before a custom repository is considered. Do not co-load the original plugin with this fork: they share the extension/module identity. `loadFromFile` does **not** download URLs. The registry resolves the version to:

<https://github.com/nictru/nft-parquet/releases/download/v0.1.0/nft-parquet-0.1.0.jar>

nf-test caches registry downloads under the current directory's `.nf-test/plugins`. Publish a new version rather than replacing an existing release asset. For an immutable registry index, replace `main` in the registry URL with the release tag or a commit containing that version's entry.

### Download the GitHub release manually

If you prefer not to configure a custom registry, download and verify the JAR and checksum from the [v0.1.0 release](https://github.com/nictru/nft-parquet/releases/tag/v0.1.0):

```bash
mkdir -p .nf-test-plugins
cd .nf-test-plugins
curl -fLO https://github.com/nictru/nft-parquet/releases/download/v0.1.0/nft-parquet-0.1.0.jar
curl -fLO https://github.com/nictru/nft-parquet/releases/download/v0.1.0/nft-parquet-0.1.0.jar.sha256
sha256sum -c nft-parquet-0.1.0.jar.sha256
cd ..
```

Load the downloaded file in `nf-test.config` (paths are relative to the directory where nf-test runs):

```groovy
config {
    plugins {
        loadFromFile '.nf-test-plugins/nft-parquet-0.1.0.jar'
    }
}
```

Alternatively, run `nf-test test --plugins .nf-test-plugins/nft-parquet-0.1.0.jar`. Use either the custom registry or the local JAR, not both. Add `.nf-test-plugins/` to your pipeline repository's `.gitignore`.

### Build locally

```bash
git clone https://github.com/nictru/nft-parquet.git
cd nft-parquet
git checkout v0.1.0
./gradlew check pluginJar
python3 scripts/validate-distribution.py
```

Use Java 17 or 21 to run Gradle. Load the resulting JAR with `loadFromFile '/absolute/path/to/nft-parquet/build/libs/nft-parquet-0.1.0.jar'`.

`pluginJar` builds the dependency-bundled `build/libs/nft-parquet-0.1.0.jar`. The `-thin.jar` from the ordinary `jar` task is not a standalone distribution. `META-INF/nf-test-plugin` keeps module name `nft-parquet`; its version is generated from `gradle.properties`.

## Assertions

Within an nf-test assertion block:

```groovy
def table = parquet('results/example.parquet') // String or java.nio.file.Path
// Equivalent Path extensions:
def same = path('results/example.parquet').parquet
def also = path('results/example.parquet').parquet()
def explicit = path('results/example.parquet').parquet([:])

assert table.rowCount == 3L
assert table.rowCount() == 3L
assert table.columnNames == ['id', 'name']
assert table.rows[0].name == 'Alice'
assert table.rowGroupCount >= 1
assert table.compressionCodecs == ['SNAPPY']
```

Files must be local. String paths are literal filenames, including spaces and colons; they are passed to `Paths.get`, not interpreted as URI schemes. Path arguments must use the local filesystem provider. There is no persistent cache; do not assume a wrapper snapshots a file that is subsequently replaced. Empty options are accepted for compatibility; nonempty options are rejected rather than silently ignored.

| Property / method | Result |
| --- | --- |
| `rowCount`, `rowCount()` | `long` count from footer metadata; does not decode all rows |
| `columnNames` | Ordered top-level column names |
| `schema` | Portable nested map, described below |
| `rowGroupCount` | Number of row groups |
| `compressionCodecs` | Sorted, distinct list of codec names |
| `rowGroups` | Footer metadata maps, described below |
| `rows` | Eager list of row maps; reads the full file into memory |
| `read(MyRecord)` | Eager list mapped by Carpet to a Java record class |

Supply an actual compiled Java record class to `read(Class)`, for example `record Person(long id, String name) {}`. Field mapping follows Carpet's record mapping rules. Null-to-primitive and narrowing numeric conversions fail rather than silently replacing values with zero or losing precision; use boxed nullable types such as `Double` when appropriate.

`schema` has shape `{name, fields: [...]}`. Each field contains `name`, `repetition`, `nullable` (`repetition == 'OPTIONAL'`), `physicalType` (null for groups), `logicalType` (null or a map with `type` and annotation-specific details), and nested `fields` (empty for primitives). Repeated fields retain `repetition: 'REPEATED'`; inspect their nested fields for element nullability. `FIXED_LEN_BYTE_ARRAY` fields also contain `typeLength`. This avoids leaking Apache Parquet schema objects into assertions or snapshots. `columnNames` includes every top-level column even when the file contains no rows.

`rowGroups` is a list of:

```text
{index, rowCount, totalByteSize, columns: [
    {path: [nested, field, names], codec, valueCount, compressedSize, uncompressedSize}
]}
```

### Differential-expression results

Rows preserve numbers, strings, booleans, and null values rather than converting everything to text. Null is distinct from floating-point NaN and infinity:

```groovy
def result = parquet('results/contrast A:treated.parquet')
assert result.columnNames == ['gene', 'log2fc', 'pvalue', 'padj', 'group', 'contrast', 'stratum']
def row = result.rows.find { it.gene == 'TP53' }
assert row != null
assert row.group == 'treated'
assert row.contrast == 'treated_vs_control'
assert row.stratum == 'all'
def expected = 1.25d
def tol = 1e-6d
assert row.log2fc != null
assert Math.abs(row.log2fc - expected) < tol
assert row.padj == null                 // missing value, not NaN
assert row.pvalue != null
assert !Double.isNaN(row.pvalue.doubleValue())
assert !Double.isInfinite(row.pvalue.doubleValue())
// For a fixture deliberately containing non-finite values:
// assert Double.isNaN(specialRow.log2fc.doubleValue())
// assert Double.isInfinite(specialRow.pvalue.doubleValue())
```

Metadata and eager reads close their file resources before returning, including on read failures. A later failed assertion does not leave an open reader for the caller to close. Writing Parquet, SQL queries, matrix operations, and statistical calculations are outside this plugin's scope.

## Development and release

```bash
./gradlew check pluginJar
python3 scripts/validate-distribution.py
bash scripts/test-integration.sh
```

CI runs these checks on Java 17 and 21 with nf-test 0.9.5 and stable Nextflow. The distribution validator uses only Python's standard library and checks the registry, generated descriptor, and bundled JAR contents.

A maintainer explicitly publishes a version-tagged GitHub release. The release workflow checks/builds that tag and attaches the bundled JAR and its `.sha256` checksum unless both assets were already attached when publishing. It does not create releases on pushes.

Only **after** the index and release asset are public, run:

```bash
bash scripts/smoke-registry.sh 0.1.0
```

This resolves the actual registry ID in a temporary project with a fresh plugin cache; it does not substitute a local JAR. It is intentionally not a pre-release CI step.
