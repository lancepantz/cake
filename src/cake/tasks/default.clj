(ns cake.tasks.default
  (:use cake.core))

(require-tasks [cake.tasks help jar test ng compile deps release swank file
                version eval check search docs classpath coverage])

(deftask default #{help})
