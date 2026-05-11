(ns llm-memory.embed.inference4j
  "Inference4j embedding provider — all-MiniLM-L12-v2 (384d) via ONNX Runtime.

  Model auto-downloads from HuggingFace on first use.
  Runs entirely in-JVM with SIMD acceleration on Apple Silicon."
  (:require [llm-memory.embed.protocol :as proto])
  (:import [io.github.inference4j.nlp SentenceTransformerEmbedder]))

(def ^:private default-model-id "inference4j/all-MiniLM-L12-v2")
(def ^:private default-max-length 512)
(def ^:private default-dims 384)

(defrecord Inference4jEmbedder [^io.github.inference4j.nlp.SentenceTransformerEmbedder embedder
                                model-id
                                dims]
  proto/Embedder
  (embed-text [_ text]
    (.encode embedder ^String text))

  (embed-texts [_ texts]
    (mapv #(.encode embedder ^String %) texts))

  (dimensions [_]
    dims)

  (embedding-info [_]
    {:model    model-id
     :dims     dims
     :provider :inference4j}))

(defn create-embedder
  "Create an Inference4jEmbedder. Options:
    :model-id    — HuggingFace model (default: inference4j/all-MiniLM-L12-v2)
    :max-length  — token limit (default: 512)
    :dims        — vector dimensions (default: 384)"
  ([] (create-embedder {}))
  ([{:keys [model-id max-length dims]
     :or   {model-id   default-model-id
            max-length default-max-length
            dims       default-dims}}]
   (let [embedder (-> (SentenceTransformerEmbedder/builder)
                      (.modelId model-id)
                      (.maxLength (int max-length))
                      (.build))]
     (->Inference4jEmbedder embedder model-id dims))))
