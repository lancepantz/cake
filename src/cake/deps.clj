(ns cake.deps
  (:use cake uncle.core
        [cake.file :only [with-root file-exists? rmdir mv file]]
        [cake.utils :only [cake-exec]]
        [bake.core :only [log os-name os-arch]]
        [clojure.java.shell :only [sh]]
        [clojure.string :only [split join]]
        [useful.map :only [map-to map-vals]]
        [useful.fn :only [all !]])
  (:require depot.maven
            [depot.deps :as depot])
  (:import [org.apache.tools.ant.taskdefs Copy Delete]))

(def default-repos
  [["clojure"           "http://build.clojure.org/releases"]
   ["clojure-snapshots" "http://build.clojure.org/snapshots"]
   ["clojars"           "http://clojars.org/repo"]
   ["maven"             "http://repo1.maven.org/maven2"]])

(def dep-types {:dependencies         (all (! :ext) (! :test) :main)
                :dev-dependencies     (all (! :ext) (! :test) :dev)
                :ext-dependencies     (all (! :dev) (! :test) :ext)
                :ext-dev-dependencies (all (! :test) :dev :ext)
                :test-dependencies    (all :test)})

(def ^{:dynamic true} *overwrite* nil)

(defn subproject-path [dep]
  (when *config*
    (or (get *config* (str "subproject." (namespace dep) "." (name dep)))
        (get *config* (str "subproject." (name dep))))))

(defn install-subprojects! []
  (doseq [type dep-types
          dep  (keys (*project* type))]
    (when-let [path (subproject-path dep)]
      (with-root path
        (cake-exec "install")))))

(defn extract-native! [dest]
  (doseq [jars (vals @dep-jars), jar jars]
    (ant Copy {:todir dest}
      (add-zipfileset {:src jar :includes "native/**"}))
    (ant Copy {:todir dest :flatten true}
      (add-zipfileset {:src jar :includes "lib/*.jar" }))))

(defn auto-exclusions [type]
  (cond (= :dev-dependencies  type) '[org.clojure/clojure org.clojure/clojure-contrib]
        (= :test-dependencies type) '[org.clojure/clojure]))

(defn fetch-deps [type]
  (binding [depot/*repositories* default-repos
            depot/*exclusions*   (auto-exclusions type)]
    (try (depot/fetch-deps *project* (dep-types type))
         (catch org.apache.tools.ant.BuildException e
           (println "\nUnable to resolve the following dependencies:\n")
           (doall (map println (filter (partial re-matches #"\d+\) .*")
                                       (split (.getMessage e) #"\n"))))
           (println)))))

(defn deps [type]
  (get @dep-jars type))

(defn- jar-name [jar]
  (if (:long *opts*)
    (.getPath (file jar))
    (.getName (file jar))))

(defn print-deps []
  (println)
  (doseq [type (keys dep-types)]
    (when-let [jars (seq (deps type))]
      (println (str (name type) ":"))
      (doseq [jar (sort (map jar-name jars))]
        (println " " jar))
      (println))))

(let [subdir {:dependencies         ""
              :dev-dependencies     "dev"
              :ext-dependencies     "ext"
              :ext-dev-dependencies "ext/dev"
              :test-dependencies    "test"}]

  (defn copy-deps [dest]
    (let [build (file "build" "deps")
          dep-types (keys dep-types)]
      (doseq [type dep-types]
        (when-let [jars (fetch-deps type)]
          (ant Copy {:todir (file build (subdir type)) :flatten true}
            (.addFileset (:fileset (meta jars))))))
      (rmdir dest)
      (mv build dest)
      (map-to #(fileset-seq {:dir (file dest (subdir %)) :includes "*.jar"})
              dep-types))))

(defn fetch-deps! []
  (let [lib        (file (first (:library-path *project*)))
        deps-cache (file lib "deps.cache")]
    (if (and (not *overwrite*) (file-exists? deps-cache))
      (reset! dep-jars (read-string (slurp deps-cache)))
      (do (install-subprojects!)
          (println "Fetching dependencies...")
          (reset! dep-jars
                  (map-vals
                   (if (:copy-deps *project*)
                     (copy-deps lib)
                     (map-to fetch-deps (keys dep-types)))
                   #(vec (map (memfn getPath) %))))
          (extract-native! lib)
          (spit deps-cache (pr-str @dep-jars))))))
