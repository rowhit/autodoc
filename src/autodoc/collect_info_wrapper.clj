(ns autodoc.collect-info-wrapper
  (:require [clojure.string :as str]
            [leiningen.core.project :as project])

  (:use [autodoc.params :only (params expand-classpath)]
        [autodoc.pom-tools :only (get-dependencies)]
        [clojure.java.io :only [file reader]]
        [clojure.java.shell :only [sh]]
        [clojure.pprint :only [cl-format pprint]]
        [leiningen.core.classpath :only [get-classpath]])

  (:import [java.io File]))

;;; The code to execute collect-info in a separate process

;;; Get the lein-style reference for autodoc collect by parsing the classpath
;;; This is defined as a macro so it will be evaluated at compile time. Once the
;;; uberjar is created, the original classpath is gone and we can't find the
;;; version that we're interested in.

(defmacro autodoc-collect
  []
  (let [props (str/split (System/getProperty "java.class.path") #":")
        version (second (some #(re-find #"/autodoc-collect-([^/]+)\.jar$" %) props))]
    `['autodoc/autodoc-collect ~version]))

(defn- build-sh-args [args]
  (concat (str/split (first args) #"\s+") (rest args)))

(defn system [& args]
  (println (str/join " " (map pr-str args)))
  (println (:out (apply sh (build-sh-args args)))))

(defn path-str [path-seq]
  (apply str (interpose (System/getProperty "path.separator")
                        (map #(.getAbsolutePath (file %)) path-seq))))

(defn autodoc-jar
  "Sort through the classpath and see if the autodoc jar is in there. This is an indication
that autodoc was invoked from a jar rather than out of its source directory."
  []
  (seq (filter #(re-matches #".*/autodoc-[^/]+\.jar$" %)
               (str/split (get (System/getProperties) "java.class.path")
                          (re-pattern (System/getProperty "path.separator"))))))

(defn exec-clojure [class-path & args]
  (apply system (concat [ "java" "-cp"]
                        [(path-str class-path)]
                        ["clojure.main" "-e"]
                        args)))

(defn expand-jar-path [jar-dirs]
  (apply concat
         (for [jar-dir jar-dirs]
           (filter #(.endsWith (.getName %) ".jar")
                   (file-seq (java.io.File. jar-dir))))))

(defn do-collect
  "Collect the namespace and var info for the checked out branch by spawning a separate process.
This means that we can keep versions and dependencies unentangled "
  [branch-name]
  (let [src-path (map #(.getPath (File. (params :root) %)) (params :source-path))
        target-path (when-not (params :built-clojure-jar)
                      (.getPath (File. (params :root) "target/classes")))
        class-path (concat
                    (filter
                     identity
                     (concat
                      [(params :built-clojure-jar)]
                      ["src"]
                      src-path
                      [target-path "."]))
                    (when-let [deps (conj
                                     (get-dependencies (params :root) (params :dependencies) (params :dependency-exceptions))
                                     (autodoc-collect))]
                      (get-classpath {:dependencies deps
                                      :repositories project/default-repositories}))
                    (expand-classpath branch-name (params :root) (params :load-classpath))
                    (expand-jar-path (params :load-jar-dirs)))
        tmp-file (File/createTempFile "collect-" ".clj")]
    (exec-clojure class-path
                  (cl-format
                   nil
                   "~@[~a ~](use 'autodoc-collect.collect-info) (collect-info-to-file \"~a\" \"~a\" \"~a\" \"~a\" \"~a\" \"~a\" \"~a\")"
                   (params :collect-prefix-forms)
                   (params :root)
                   (str/join ":" (params :source-path))
                   (str/join ":" (params :namespaces-to-document))
                   (str/join ":" (params :load-except-list))
                   (params :trim-prefix)
                   (.getAbsolutePath tmp-file)
                   branch-name))
    (try
      (with-open [f (java.io.PushbackReader. (reader tmp-file))]
        (binding [*in* f] (read)))
      (finally
        (.delete tmp-file)))))
