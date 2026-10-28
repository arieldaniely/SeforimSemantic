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

## Optional search modes

`VectorSearcher.search(vector, SemanticSearchOptions(...))` supports:

- Defaults: existing approximate top-150 retrieval.
- `exactTopK = true`: scan every eligible stored vector, retain only K best ids.
  This avoids missing nearest neighbors in disconnected or incomplete ANN traversal.
- `minimumSimilarity = 0.85f`: return **every** live eligible vector with normalized
  cosine at least 0.85, across all segments and shards. No candidate ceiling applies.
- `maximumResults = 500`: an explicit optional threshold cap. Null means uncapped.
- `checkCancelled`: callback during traversal, allowing the caller's coroutine to cancel.

Scores use Lucene COSINE: `(1 + cosine) / 2`; 0.85 corresponds to cosine 0.70.
They are not calibrated relevance probabilities, and int8 scores compare quantized
vectors, not the original floating point model output. RRF fusion scores have a
different scale and must never be used as a confidence threshold.

`SemanticFusion.rank` optionally weights each ranker. In threshold mode lexical
hits can boost the ordering of qualifying dense hits, but cannot bypass the
threshold. A cap is applied after fusion. `QueryEmbeddingCache` provides a bounded,
per-model LRU keyed by normalized text and role, with defensive array copies.

Threshold scanning takes O(eligible vectors), plus sorting matches. It stores ids
and scores for qualifying results, so a very low uncapped threshold can use
substantial memory. Exact top-K and capped vector retrieval keep a bounded heap;
the hybrid adapter retains all qualifying ids until fusion when its final cap is
applied. Database text and snippets remain the caller's responsibility and are
loaded only for displayed pages. Base-book and book-id filters remain supported.

## Reproduce comparisons

```powershell
.\gradlew.bat jvmTest benchmarkSemanticSearch `
  '-PsemanticModelDir=C:\path\to\model' `
  '-PsemanticIndexDir=C:\path\to\index' `
  '-PsemanticReport=C:\path\to\comparison.csv' `
  -PbenchmarkQueries=3 -PbenchmarkRepetitions=3
```

The benchmark enables Java's incubating Vector API, as the parent app already
does, warms inference, alternates method order between repetitions, and compares
top-K with exact neighbors and several uncapped thresholds, both globally and
with the base-book filter. CSV exports timings, aggregate counts and nearest-neighbor
recall only. Exact-neighbor recall measures retrieval coverage, not human relevance.
The queries are listed in `BenchmarkSemanticSearch.kt`. Model load and the first
index traversal include cold costs; repeat and inspect per-query samples before
drawing performance conclusions.

The public [upstream audit](docs/semantic-upstream-audit-he.md) and
[experiment report](docs/semantic-search-experiments-he.md) include pinned source
links, raw CSV samples, measured limitations and the extraction commit chain.
