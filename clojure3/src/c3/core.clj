(ns c3.core)

(defn filter-block-groups [pred groups]
  (lazy-seq
   (loop [groups (seq groups)]
     (when groups
       (let [tasks (mapv #(future (doall (filter pred %))) (first groups))
             result (mapcat deref tasks)]
         (if (seq result)
           (concat result (filter-block-groups pred (rest groups)))
           (recur (next groups))))))))

(defn parallel-filter
  ([pred coll]
   (parallel-filter pred coll 128 4))
  ([pred coll block-size parallelism]
   (filter-block-groups pred (partition-all parallelism
                                          (partition-all block-size coll)))))

(defn -main [& _]
  (try
    (prn (take 20 (parallel-filter even? (iterate inc 0) 8 3)))
    (finally (shutdown-agents))))
