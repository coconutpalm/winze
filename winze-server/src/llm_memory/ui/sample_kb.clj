(ns llm-memory.ui.sample-kb
  "Install the bundled sample knowledge base to the user's data directory."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [hyperfiddle.rcf :refer [tests]]))

(defn- target-dir
  "OS-appropriate user data directory for the sample KB."
  []
  (let [home (System/getProperty "user.home")]
    (if (str/starts-with? (System/getProperty "os.name") "Mac")
      (io/file home "Library" "Application Support" "Winze" "sample-kb")
      (io/file (or (System/getenv "XDG_DATA_HOME")
                   (str home "/.local/share"))
               "winze" "sample-kb"))))

(defn- enumerate-resources
  "Return a seq of [leaf-name URL] pairs for all files under the given
  classpath prefix. Works inside JARs (jar: URLs) and on the filesystem
  (file: URLs). Never use (io/file (io/resource ...)) — it fails in uberjars."
  [prefix]
  (let [loader (clojure.lang.RT/baseLoader)]
    (when-let [root-url (.getResource loader prefix)]
      (case (.getProtocol root-url)
        "file"
        (->> (.listFiles (io/file root-url))
             (filter #(.isFile %))
             (sort-by #(.getName %))
             (map (fn [f] [(.getName f) (.toURL (.toURI f))])))

        "jar"
        (let [conn     (.openConnection root-url)
              jar-file (.getJarFile conn)]
          (->> (enumeration-seq (.entries jar-file))
               (filter #(and (str/starts-with? (.getName %) prefix)
                             (not (str/ends-with? (.getName %) "/"))))
               (sort-by #(.getName %))
               (map (fn [entry]
                      (let [leaf (last (str/split (.getName entry) #"/"))]
                        [leaf (.getResource loader (.getName entry))])))))

        nil))))

(defn install!
  "Unpack bundled sample-kb resources to the user data directory and register
  the directory. Returns {:status :installed|:already-installed :path <str>}."
  []
  (let [target (target-dir)]
    (.mkdirs target)
    (if (and (.isDirectory target) (seq (.listFiles target)))
      (do (log/info "sample-kb already installed at" (.getAbsolutePath target))
          {:status :already-installed :path (.getAbsolutePath target)})
      (let [pairs (enumerate-resources "sample-kb/")]
        (doseq [[leaf url] pairs]
          (when (seq leaf)
            (with-open [r (io/reader url :encoding "UTF-8")
                        w (io/writer (io/file target leaf) :encoding "UTF-8")]
              (io/copy r w))))
        (let [n (count (.listFiles target))]
          (log/info "sample-kb installed" n "file(s) to" (.getAbsolutePath target))
          {:status :installed
           :path   (.getAbsolutePath target)
           :file-count n})))))

(tests
 "target-dir returns a File"
 (instance? java.io.File (target-dir)) := true
 "enumerate-resources returns pairs for sample-kb/"
 (let [pairs (enumerate-resources "sample-kb/")]
   (pos? (count pairs))) := true
 :rcf)
