(ns llm-memory.embed.protocol
  "Abstraction over text-to-vector embedding providers.

  Implementations must produce fixed-dimension float arrays from text.
  The protocol allows swapping embedding backends (inference4j, Datalevin
  built-in, Ollama, etc.) without changing the indexing or search code.")

(defprotocol Embedder
  (embed-text   [this text]   "Embed a single string. Returns float[].")
  (embed-texts  [this texts]  "Embed a seq of strings. Returns [float[] ...].
                                Default: map embed-text (override for batching).")
  (dimensions   [this]        "Return the dimensionality of the vectors (int).")
  (embedding-info [this]      "Return {:model \"...\" :dims N :provider :keyword}."))
