(ns runner
  "Runs all subproject tests.  Intended to be used from the REPL to ensure
   all tests are passing before a commit or PR."
  (:require [clojure.string :as str]
            [clojure.java.classpath :as cp]
            [clojure.tools.namespace.find :as nss]
            [clojure.test :refer [run-tests]]))

(def project-name "winze-server")

(def test-directory
  (->>
   (cp/classpath-directories)
   (map #(.getCanonicalFile %))
   distinct
   (filter #(str/ends-with? % (str project-name "/src")))
   first))

(defn test-namespaces
  []
  (->> [test-directory]
       (nss/find-namespaces)))

(defn require-all-test-nss
  []
  (dorun (doseq [test-ns (test-namespaces)]
           (require [test-ns]))))

(defn test-everything!
  []
  (require-all-test-nss)
  (apply run-tests (test-namespaces)))

(defn -main
  "While this can be used as a test runner from the command line, we don't
   use it this way; rather we use the eftest runner because it's supported by
   cloverage for coverage reports.  See the `Makefile` for details."
  [& _args]
  (test-everything!))

(comment
  (test-namespaces)
  (test-everything!))