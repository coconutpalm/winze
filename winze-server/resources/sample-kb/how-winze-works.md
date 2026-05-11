---
tags: [architecture, indexing, embedding]
---
# How Winze Works

Winze indexes your markdown planning documents and makes them searchable by
meaning, not just keywords.

## Indexing pipeline

1. **Chunking** — each `.md` file is split into overlapping text chunks
   (~500 tokens each with 100-token overlap).
2. **Embedding** — each chunk is converted to a 384-dimensional vector using
   the `all-MiniLM-L12-v2` model (runs entirely offline — no API calls).
3. **Storage** — chunks and their vectors are stored in a Datalevin HNSW index
   at `~/.local/share/winze/.datalevin/`.
4. **Watching** — a filesystem watcher (FSEvents on macOS, inotify on Linux)
   automatically re-indexes files as they change.

## Search

When you type a query, Winze:

1. Embeds your query into the same 384-dimensional space.
2. Finds the nearest chunks by cosine similarity (HNSW approximate search).
3. Groups results by source file and renders them as cards.

Because search operates on meaning vectors, queries like
_"what caused the cache miss"_ find relevant content even if those exact words
don't appear in the document.

## Metadata

Winze infers metadata (status, doc_type, group) from file naming conventions
with ~97% coverage. Optional YAML frontmatter in each file overrides inferred
values. See [Metadata Conventions](metadata-conventions.md).

## Multi-root

You can register multiple folders. Each gets its own watcher and shares the
same search index, so a single query spans all your projects.
