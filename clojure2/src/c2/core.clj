(ns c2.core)

(defn- mark-next [table number step]
  (let [next-number (+' number step)]
    ;; If already marked, this multiple will be removed by another prime.
    (if (contains? table next-number)
      (recur table next-number step)
      (assoc table next-number step))))

(defn- sieve [candidate table]
  (lazy-seq
   (loop [n candidate, composites table]
     (if-let [step (get composites n)]
       (recur (inc' n) (mark-next (dissoc composites n) n step))
       ;; An unmarked number is prime; start marking its multiples at n*n.
       (cons n (sieve (inc' n) (assoc composites (*' n n) n)))))))

(defn prime-seq
  "A fresh infinite lazy sequence of primes."
  []
  (sieve 2 {}))

(def primes (prime-seq))

(defn -main [& _]
  (prn (take 25 primes)))
