# Search Quality & Relevance Scores

Search results include a **relevance percentage** (cosine similarity between query and
chunk embeddings) and a badge: **strong** (>50%), **partial** (30–50%), or **weak** (<30%).

## What the scores mean

The embedding model (`all-MiniLM-L12-v2`, 384d) produces scores that reflect genuine
semantic similarity. Scores are **not** percentages of "correctness" — they measure how
much the query's meaning overlaps with the chunk's content.

| Score range | What it means | Typical query style |
|-------------|--------------|---------------------|
| **70–80%** | Query substantially overlaps the chunk's content | 8+ domain-specific terms, or a full sentence using the chunk's terminology |
| **55–70%** | Strong topical match | Natural language question with several relevant terms |
| **35–55%** | Partial match — right topic area, not exact | Short natural language queries, exploratory search |
| **<35%** | Weak or tangential | Very short queries, different vocabulary |

For typical exploratory search (short natural language queries), **35–55% is normal and
expected**. The top result is almost always relevant even at these scores — the percentage
reflects *degree of overlap*, not *confidence*.

## Tips for better search results

- **Use domain-specific terms** that appear in the target documents — the model rewards
  lexical overlap in technical contexts
- **Write at least a full sentence** — short keyword lists (2–3 words) max out around
  40% because the model needs semantic structure to match against prose
- **Cover multiple concepts** — a query about just one aspect of a multi-topic chunk
  scores lower than one that touches several of its key points
- **Natural language works well** — a well-formed question with domain terms scores
  nearly as high as a keyword spray

## Score examples by query style

These examples are from real queries against the same document chunk (a ~4000-char
section about uberjar packaging with CDT/SWT):

```
80%  8-10 domain keywords covering the chunk's core concepts:
     "uberjar packaging CDT SWT DynamicClassLoader repl binding add-libs"

73%  Natural language sentence with domain terminology:
     "How to package a CDT SWT application into an uberjar with dynamic class loading"

74%  Near-verbatim paraphrase of the opening paragraph:
     "CDT dynamically loads platform-specific SWT native libraries at runtime
      using clojure.repl.deps add-libs"

70%  Multi-sentence paraphrase covering several sub-points:
     "Declare Clojure 1.12 explicitly in deps.edn. Set up a DynamicClassLoader
      and bind repl to true. AOT-compile only main."

50%  3-4 technical terms (too few to anchor):
     "DynamicClassLoader add-libs repl binding"

38%  2-3 generic keywords:
     "uberjar CDT SWT"
```

## How relevance is calculated

1. Query text and document chunks are embedded into 384-dimensional vectors using
   `all-MiniLM-L12-v2` (via inference4j / ONNX Runtime, in-JVM)
2. Datalevin's HNSW index finds the nearest chunk vectors
3. **Cosine similarity** is computed: `dot(query, chunk) / (‖query‖ × ‖chunk‖)`
4. The similarity value (0.0–1.0) is displayed directly as the percentage

No rescaling or sigmoid transformation is applied — the percentage is the raw cosine
similarity. This makes scores intuitive and comparable across queries.

## Embedding model

| Property | Value |
|----------|-------|
| **Model** | `all-MiniLM-L12-v2` (sentence-transformers) |
| **Dimensions** | 384 |
| **Parameters** | ~33M |
| **Runtime** | inference4j (ONNX Runtime, in-JVM, SIMD on Apple Silicon) |
| **Download** | ~127MB, auto-downloaded from HuggingFace on first server start |
| **Latency** | ~30ms per embedding on Apple Silicon |

### Why this model

- **Same 384d as the previous model** (all-MiniLM-L6-v2) — no schema change needed
- **12 layers vs 6** — deeper model produces better semantic representations
- **Better discrimination** — unrelated content scores near zero (or negative), while
  L6 scored unrelated content at ~20%. The gap between relevant and irrelevant is wider.
- **Lightweight** — 33M parameters, imperceptible CPU usage for interactive queries

### Upgrade path

For significantly better natural language search quality, consider `bge-base-en-v1.5`
or `nomic-embed-text-v1.5` (768d, ~109–137M params, ~100ms/query). These require a
dimension change in the Datalevin schema and a full reindex. The `nomic` model supports
query/document prefixes which can further boost retrieval accuracy for short queries
against long chunks.
