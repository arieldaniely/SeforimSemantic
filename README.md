# Seforim Semantic Open

Open source JVM semantic retrieval for Hebrew and Aramaic texts, extracted from
the arieldaniely forks of Zayit and SeforimLibrary. Builds independently using
public Maven Central dependencies, without a GitHub package token or Android SDK.

Powered by the technologies that drive Zayit — https://zayitapp.com/

## Build and use

Requires JDK 25. Run `./gradlew jvmTest jvmJar` (Windows: `gradlew.bat`).
Clone the parent app with `git clone --recurse-submodules`.

The parent SeforimLibrary includes this repository as a Gradle composite build and
exports `io.github.arieldaniely:seforim-semantic-open:0.1.0` through its search API.
Existing `io.github.kdroidfilter.seforimlibrary.search` package names are retained
for source compatibility. There is no dependency from this repository back to
SeforimLibrary or the app.

This repository owns embedding inference, Round 2 normalization, int8 vector
quantization, Lucene vector retrieval, asset fingerprints, index construction and
optimization, and their tests. The parent owns lexical search and the hybrid
adapter, database result resolution, installation, preferences and UI.
Existing `:search:buildSemanticIndex`, `buildSemanticIndexFromVectors` and
`optimizeSemanticIndex` tasks continue to launch the extracted entry points.

## Artifacts and compatibility

The runtime accepts externally supplied Round 2 ONNX and tokenizer files and
validates index/database/model fingerprints. It does not download or include
model weights, corpus databases, tokenizers or vector indexes. The model contract
is 256 dimensions, 256 tokens and `[QUERY]` / `[PASSAGE]` roles. Existing float32
and `int8-maxabs-v1` shard bundles remain supported.

Source code uses the original AGPL v3 license and additional attribution terms,
preserved verbatim in [LICENSE](LICENSE). Model and corpus rights remain separate;
extracting source code grants no new rights to redistribute those artifacts.

## Extraction baseline

The first commit is a behavior-preserving extraction. Search experiments and
result-selection changes are committed separately so the baseline is reviewable.
