(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.dorme/winze-server)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/winze-server.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :ns-compile ['llm-memory.server.main]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'llm-memory.server.main}))
