(ns c2.core-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [c2.core :refer [primes prime-seq]]))

(defn- prime-by-division? [n]
  (and (>= n 2)
       (not-any? #(zero? (mod n %))
                 (take-while #(<= (* % %) n) (range 2 n)))))

(deftest known-primes
  (is (= [2 3 5 7 11 13 17 19 23 29 31 37 41 43 47 53 59 61 67 71]
         (vec (take 20 primes))))
  (is (= 7919 (nth (prime-seq) 999)))
  (is (= 104729 (nth (prime-seq) 9999))))

(deftest independent-reference
  (let [actual (vec (take-while #(< % 10000) (prime-seq)))
        expected (vec (filter prime-by-division? (range 2 10000)))]
    (is (= expected actual))
    (is (apply < actual))
    (is (= (count actual) (count (set actual))))))

(deftest composite-collisions-and-squares
  (let [values (set (take-while #(< % 250) (prime-seq)))]
    (doseq [n [4 9 25 49 121 169 30 60 90 105 210]]
      (is (not (contains? values n))))
    (doseq [n [29 31 47 53 113 127 167 173 211]]
      (is (contains? values n)))))

(deftest lazy-and-repeatable
  (let [values (prime-seq)]
    (is (not (realized? values)))
    (is (empty? (take 0 values)))
    (is (not (realized? values)))
    (is (= 2 (first values)))
    (is (realized? values))
    (is (= (take 100 values) (take 100 (prime-seq))))
    (is (= [2 3 5] (vec (take 3 values))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'c2.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
