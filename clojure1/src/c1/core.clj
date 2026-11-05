(ns c1.core)

(defn extend-string [s alphabet]
  (map #(str s %)
       (remove #(= (last s) (first %)) alphabet)))

(defn extend-all [strings alphabet]
  (reduce (fn [result s]
            (reduce (fn [words word] (cons word words))
                    result
                    (extend-string s alphabet)))
          '()
          strings))

(defn strings-without-repeats
  "All length-n words with unequal adjacent characters. Order is unspecified."
  [alphabet n]
  (reduce (fn [strings _] (extend-all strings alphabet))
          '("")
          (range n)))

(defn -main [& _]
  (prn (strings-without-repeats '("a" "b" "c") 2)))
