(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as deploy]))

(def lib 'io.github.dorme/clj-llm-memory)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url "https://github.com/dorme/clj-llm-memory"}})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println (str "Built " jar-file)))

(defn install [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println (str "Installed " lib " " version " to local Maven repo")))

(defn deploy [_]
  (jar nil)
  (deploy/deploy {:installer :remote
                  :artifact  (b/resolve-path jar-file)
                  :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
