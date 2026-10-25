# Changelog

## 0.1.0 — 2026-10-25

- Add direct Carpet/Apache Parquet readers for local files, with nf-test helper and Path extension APIs.
- Provide footer-based row counts, portable nested schemas, column names, row-group metadata, and compression codecs.
- Support eager row maps and Java record mapping.
- Build a standalone dependency-bundled plugin JAR while leaving Groovy to the host.
- Add Java 17/21 CI, distribution validation, and manually initiated release distribution.
- Publish through the GitHub-hosted custom registry using the unique ID `nictru-nft-parquet`.
