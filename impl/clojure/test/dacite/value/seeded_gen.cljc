(ns dacite.value.seeded-gen
  "A small seeded stand-in for the parts of test.check the shared HAMT
   checks use, for nbb, where test.check is not available. A generator is a
   function of a random source; `quick-check` runs a fixed number of cases
   from a fixed seed, so a run is the same every time. There is no
   shrinking: a failure reports the case as drawn."
  (:refer-clojure :exclude [vector]))

(defn- next-int!
  "Next 31-bit value of the LCG in `rng` (a volatile)."
  [rng]
  (vswap! rng (fn [x]
                #?(:clj (bit-and (+ (unchecked-int (* (long x) 1103515245)) 12345) 0x7fffffff)
                   :cljs (bit-and (+ (js/Math.imul x 1103515245) 12345) 0x7fffffff)))))

(defn choose
  "An int in lo..hi, inclusive."
  [lo hi]
  (fn [rng] (+ lo (mod (next-int! rng) (inc (- hi lo))))))

(defn return
  [x]
  (fn [_] x))

(defn elements
  [coll]
  (let [v (vec coll)]
    (fn [rng] (nth v (mod (next-int! rng) (count v))))))

(defn tuple
  [& gs]
  (fn [rng] (mapv #(% rng) gs)))

(defn vector
  "`n` draws of `g`, or between `lo` and `hi` of them."
  ([g n] (fn [rng] (vec (repeatedly n #(g rng)))))
  ([g lo hi] (fn [rng] ((vector g ((choose lo hi) rng)) rng))))

(defn vector-distinct
  "Distinct draws of `g`, between :min-elements and :max-elements of them
   (fewer if `g` runs out of new values in 100 tries)."
  [g {:keys [min-elements max-elements]}]
  (fn [rng]
    (let [n ((choose min-elements max-elements) rng)]
      (loop [out [] seen #{} tries 0]
        (if (or (= n (count out)) (= tries 100))
          out
          (let [x (g rng)]
            (if (contains? seen x)
              (recur out seen (inc tries))
              (recur (conj out x) (conj seen x) (inc tries)))))))))

(defn frequency
  "[[weight g] ...]: `g` with chance weight / total."
  [pairs]
  (let [total (reduce + (map first pairs))]
    (fn [rng]
      (loop [r (mod (next-int! rng) total) [[w g] & more] pairs]
        (if (< r w) (g rng) (recur (- r w) more))))))

(defn bind
  [g f]
  (fn [rng] ((f (g rng)) rng)))

(defn fmap
  [f g]
  (fn [rng] (f (g rng))))

(defn quick-check
  "Run `(prop x)` on `n` draws of `g` from `seed`. {:pass? :seed} and, on a
   failure, the drawn case as :shrunk (the name test.check uses)."
  [n g prop seed]
  (let [rng (volatile! seed)]
    (loop [i 0]
      (if (= i n)
        {:pass? true :seed seed :num-tests n}
        (let [x (g rng)]
          (if (prop x)
            (recur (inc i))
            {:pass? false :seed seed :shrunk x}))))))
