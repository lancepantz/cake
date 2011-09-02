(ns bake.test
  (:use clojure.test
        [cake :only [*config*]]
        [bake.core :only [verbose? log as-fn]]
        [bake.reload :only [last-reloaded last-modified reload]]
        [bake.notify :only [notify]])
  (:import [java.io StringWriter IOException]))

(do
  ;; these functions were written by Mark McGranaghan
  ;; https://github.com/mmcgrana/clj-stacktrace

  (defn re-gsub
    "Simple version of re-gsub that only supports string replacements."
    [^java.util.regex.Pattern regex replacement ^String string]
    (.. regex (matcher string) (replaceAll replacement)))

  (defn re-match?
    "Returns true iff the given string contains a match for the given pattern."
    [^java.util.regex.Pattern pattern string]
    (.find (.matcher pattern string)))

  (defn re-get
    "Returns the nth captured group resulting from matching the given pattern
  against the given string, or nil if no match is found."
    [re s n]
    (let [m (re-matcher re s)]
      (if (.find m)
        (.group m n))))

  (defn- clojure-code?
    "Returns true if the filename is non-null and indicates a clj source file."
    [class-name file]
    (or (re-match? #"^user" class-name)
        (and file (re-match? #"\.clj$" file))))

  (defn- clojure-ns
    "Returns the clojure namespace name implied by the bytecode class name."
    [class-name]
    (re-gsub #"_" "-" (re-get #"([^$]+)\$" class-name 1)))

  ; drop everything before and including the first $
  ; drop everything after and including and the second $
  ; drop any __xyz suffixes
  ; sub _PLACEHOLDER_ for the corresponding char
  (def clojure-fn-subs
    [[#"^[^$]*\$" ""]
     [#"\$.*"    ""]
     [#"__\d+.*"  ""]
     [#"_QMARK_"  "?"]
     [#"_BANG_"   "!"]
     [#"_PLUS_"   "+"]
     [#"_GT_"     ">"]
     [#"_LT_"     "<"]
     [#"_EQ_"     "="]
     [#"_STAR_"   "*"]
     [#"_SLASH_"  "/"]
     [#"_"        "-"]])

  (defn- clojure-fn
    "Returns the clojure function name implied by the bytecode class name."
    [class-name]
    (reduce
     (fn [base-name [pattern sub]] (re-gsub pattern sub base-name))
     class-name
     clojure-fn-subs))

  (defn- clojure-annon-fn?
    "Returns true if the bytecode class name implies an anonymous inner fn."
    [class-name]
    (re-match? #"\$.*\$" class-name))

  (defn parse-trace-elem
    "Returns a map of information about the java trace element.
  All returned maps have the keys:
  :file      String of source file name.
  :line      Number of source line number of the enclosing form.
  Additionally for elements from Java code:
  :java      true, to indicate a Java elem.
  :class     String of the name of the class to which the method belongs.
  Additionally for elements from Clojure code:
  :clojure   true, to inidcate a Clojure elem.
  :ns        String representing the namespace of the function.
  :fn        String representing the name of the enclosing var for the function.
  :annon-fn  true iff the function is an anonymous inner fn."
    [elem]
    (let [class-name (.getClassName elem)
          file       (.getFileName  elem)
          line       (let [l (.getLineNumber elem)] (if (> l 0) l))
          parsed     {:file file :line line}]
      (if (clojure-code? class-name file)
        (assoc parsed
          :clojure true
          :ns       (clojure-ns class-name)
          :fn       (clojure-fn class-name)
          :annon-fn (clojure-annon-fn? class-name))
        (assoc parsed
          :java true
          :class class-name
          :method (.getMethodName elem)))))

  (defn parse-trace-elems
    "Returns a seq of maps providing usefull information about the java stack
  trace elements. See parse-trace-elem."
    [elems]
    (map parse-trace-elem elems))

  (defn- trim-redundant
    "Returns the portion of the tail of causer-elems that is not duplicated in
  the tail of caused-elems. This corresponds to the \"...26 more\" that you
  see at the bottom of regular trace dumps."
    [causer-parsed-elems caused-parsed-elems]
    (loop [rcauser-parsed-elems (reverse causer-parsed-elems)
           rcaused-parsed-elems (reverse caused-parsed-elems)]
      (if-let [rcauser-bottom (first rcauser-parsed-elems)]
        (if (= rcauser-bottom (first rcaused-parsed-elems))
          (recur (next rcauser-parsed-elems) (next rcaused-parsed-elems))
          (reverse rcauser-parsed-elems)))))

  (defn- parse-cause-exception
    "Like parse-exception, but for causing exceptions. The returned map has all
  of the same keys as the map returned by parse-exception, and one added one:
  :trimmed-elems  A subset of :trace-elems representing the portion of the
                  top of the stacktrace not shared with that of the caused
                  exception."
    [causer-e caused-parsed-elems]
    (let [parsed-elems (parse-trace-elems (.getStackTrace causer-e))
          base {:class         (class causer-e)
                :message       (.getMessage causer-e)
                :trace-elems   parsed-elems
                :trimmed-elems (trim-redundant parsed-elems caused-parsed-elems)}]
      (if-let [cause (.getCause causer-e)]
        (assoc base :cause (parse-cause-exception cause parsed-elems))
        base)))

  (defn parse-exception
    "Returns a Clojure map providing usefull informaiton about the exception.
  The map has keys
  :class        Class of the exception.
  :message      Regular exception message string.
  :trace-elems  Parsed stack trace elems, see parse-trace-elem.
  :cause        See parse-cause-exception."
    [e]
    (let [parsed-elems (parse-trace-elems (.getStackTrace e))
          base {:class       (class e)
                :message     (.getMessage e)
                :trace-elems parsed-elems}]
      (if-let [cause (.getCause e)]
        (assoc base :cause (parse-cause-exception cause parsed-elems))
        base))))

(defn get-test-vars [namespaces opts]
  (let [[tags functions ns-opts] (map opts [:tags :functions :namespaces])
        ns-opts (as-fn ns-opts)
        run? (fn [ns]
               (if (ns-opts ns)
                 (if (ns-resolve ns 'test-ns-hook)
                   (comp #{'test-ns-hook} first)
                   (comp :test meta second))
                 (fn [[fn-name f]]
                   (or (some tags (:tags (meta f)))
                       (functions (apply symbol (map name [ns fn-name])))))))
        get-tests-for-ns (fn [ns]
                           (require ns)
                           (for [f (filter (run? ns) (ns-publics ns))]
                             (key f)))]
    (reduce (fn [acc ns]
              (if-let [test-fns (seq (get-tests-for-ns ns))]
                (assoc acc ns (doall test-fns))
                acc))
            {}
            namespaces)))

(declare *ns-results* *current-test*)

(defn update-results [m]
  (swap! *ns-results* update-in [:tests *current-test* :assertions] (fnil conj []) m))

(defmulti my-report :type)

(defmethod my-report :pass [m]
  (update-results (dissoc m :actual)))

(defmethod my-report :fail [m]
  (update-results m))

(defmethod my-report :error [m]
  (update-results (update-in m [:actual] parse-exception)))

;; the methods below are never called because i'm calling test-var directly

(defmethod my-report :default [m]
  (prn :here))

(defmethod my-report :summary [m]
  (prn :here))

(defmethod my-report :begin-test-ns [m]
  (prn :here))

(defmethod my-report :end-test-ns [m]
  (prn :here))

(defmethod my-report :begin-test-var [m]
  (set! *current-test* (:name (meta (:var m))))
  (set! *out* (StringWriter.))
  (set! *err* *out*))

(defmethod my-report :end-test-var [m]
  (swap! *ns-results* assoc-in [:tests *current-test* :out] (not-empty (.toString *out*)))
  (set! *current-test* nil))

(defn run-ns-tests [ns tests]
  (let [ns-meta (meta (find-ns ns))
        each-fixtures (join-fixtures (:clojure.test/each-fixtures ns-meta))
        once-fixtures (join-fixtures (:clojure.test/once-fixtures ns-meta))]
    (require ns)
    (binding [report my-report
              *test-out* (StringWriter.)
              *out* *out* ;; this is so it gets restored
              *err* *err*
              *report-counters* (ref *initial-report-counters*)
              *ns-results* (atom {:tests {}})
              *current-test* nil]
      (if (= '(test-ns-hook) tests)
        ((var-get (ns-resolve ns 'test-ns-hook)))
        (once-fixtures
         (fn []
           (doseq [test tests]
             (each-fixtures
              (fn []
                (test-var (ns-resolve ns test))))))))
      @*ns-results*)))