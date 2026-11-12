(ns c3.benchmark
  (:require [c3.core :refer [parallel-filter]]))

(defn slow-even? [n]
  (Thread/sleep 10)
  (even? n))

(defn -main [& _]
  (try
    (let [numbers (range 200)]
      (println "filter:")
      (time (dorun (filter slow-even? numbers)))
      (println "parallel-filter (block-size=25, parallelism=4):")
      (time (dorun (parallel-filter slow-even? numbers 25 4))))
    (finally (shutdown-agents))))
