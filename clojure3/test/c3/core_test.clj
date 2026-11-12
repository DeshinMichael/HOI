(ns c3.core-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [c3.core :refer [parallel-filter]])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest same-results-as-filter
  (doseq [input [nil [] '(1 2 3 4 5) (vec (range 73))]
          pred [even? odd? (constantly true) (constantly false)]
          block-size [1 4 16]
          parallelism [1 3]]
    (is (= (vec (filter pred input))
           (vec (parallel-filter pred input block-size parallelism)))))
  (is (= [nil false :ok]
         (vec (parallel-filter (constantly true) [nil false :ok] 2 2))))
  (is (= [nil :ok]
         (vec (parallel-filter #(if (= % false) nil :truthy)
                               [nil false :ok] 2 2)))))

(deftest infinite-input
  (is (= (vec (take 100 (filter #(zero? (mod % 7)) (iterate inc 0))))
         (vec (take 100 (parallel-filter #(zero? (mod % 7))
                                        (iterate inc 0) 11 3)))))
  (is (= 100000 (first (parallel-filter #(>= % 100000)
                                       (iterate inc 0) 128 3)))))

(deftest many-empty-blocks
  (is (empty? (parallel-filter (constantly false) (range 10000) 1 2))))

(deftest deferred-work-and-bounded-batch
  (let [read-count (atom 0)
        calls (atom 0)
        finished (CountDownLatch. 12)
        ;; iterate, unlike range/vector, does not add chunked realization.
        input (map (fn [x] (swap! read-count inc) x) (iterate inc 0))
        output (parallel-filter (fn [_]
                                  (swap! calls inc)
                                  (.countDown finished)
                                  true)
                                input 4 3)]
    (is (zero? @read-count))
    (is (zero? @calls))
    (is (empty? (take 0 output)))
    (is (zero? @read-count))
    (is (= 0 (first output)))
    (is (.await finished 5 TimeUnit/SECONDS))
    (is (= 12 @calls))
    ;; Re-reading a realized head must not re-run its predicate.
    (is (= 0 (first output)))
    (is (= 12 @calls))))

(deftest default-options
  (is (= [0 2 4 6 8]
         (vec (parallel-filter even? (range 10))))))

(deftest later-blocks-may-finish-first
  (let [release-first (CountDownLatch. 1)
        later-finished (CountDownLatch. 2)
        result (future
                 (vec (parallel-filter
                       (fn [x]
                         (when (zero? x)
                           (when-not (.await release-first 10 TimeUnit/SECONDS)
                             (throw (ex-info "Test gate timed out" {}))))
                         (when (#{7 11} x) (.countDown later-finished))
                         true)
                       (range 12) 4 3)))]
    (try
      (is (.await later-finished 5 TimeUnit/SECONDS))
      (is (not (realized? result)))
      (.countDown release-first)
      (is (= (vec (range 12)) (deref result 10000 ::timeout)))
      (finally
        (.countDown release-first)
        (future-cancel result)))))

(deftest blocks-run-concurrently-and-preserve-order
  (let [started (CountDownLatch. 3)
        release (CountDownLatch. 1)
        threads (atom {})
        result (future
                 (vec
                  (parallel-filter
                   (fn [x]
                     (swap! threads assoc x (.getId (Thread/currentThread)))
                     (when (zero? (mod x 4))
                       (.countDown started)
                       (when-not (.await release 10 TimeUnit/SECONDS)
                         (throw (ex-info "Test gate timed out" {}))))
                     (even? x))
                   (range 12) 4 3)))]
    (try
      ;; All three block heads must start before any is allowed to finish.
      (is (.await started 5 TimeUnit/SECONDS))
      (.countDown release)
      (is (= [0 2 4 6 8 10] (deref result 10000 ::timeout)))
      (is (= 12 (count @threads)))
      (is (= 3 (count (set (vals @threads)))))
      (doseq [block (partition 4 (range 12))]
        (is (= 1 (count (set (map @threads block))))))
      (finally
        (.countDown release)
        (future-cancel result)))))

(deftest predicate-errors-propagate
  (let [result (parallel-filter (fn [x]
                                  (if (= x 5)
                                    (throw (ex-info "predicate failed" {:value x}))
                                    true))
                                (range 10) 3 2)
        error (try (doall result) nil (catch Exception e e))]
    (is (some? error))
    (when error
      (let [cause (loop [e error]
                    (if-let [inner (.getCause e)] (recur inner) e))]
        (is (= "predicate failed" (.getMessage cause)))
        (is (= {:value 5} (ex-data cause)))))))

(defn -main [& _]
  (let [summary (try (run-tests 'c3.core-test)
                     (finally (shutdown-agents)))]
    (System/exit (if (zero? (+ (:fail summary) (:error summary))) 0 1))))
