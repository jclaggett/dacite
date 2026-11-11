(ns dacite.value.seeded-gen
  "A small seeded stand-in for the parts of test.check the shared HAMT,
   hash, and peer-v1 checks use, for nbb, where test.check is not available. A generator is a
   function of a random source; `quick-check` runs a fixed number of cases
   from a fixed seed, so a run is the same every time. There is no
   shrinking: a failure reports the case as drawn.

   The number generators are smaller than test.check's: a draw is 31 bits
   (two for a `choose` over a range wider than 2^31), so `large-integer` is
   within 2^31 (nbb numbers are exact there), and `double` is a thousandth of
   an int up to a million.

   Every draw from a range (`choose`, `elements`, `one-of`, `frequency`,
   `boolean`, a sign) goes through `below`, which takes the high bits of a
   draw, never `mod`: an LCG's low bits have short periods (bit k repeats
   every 2^(k+1) draws)."
  (:refer-clojure :exclude [boolean byte char double map vector]))

(defn- next-int!
  "Next 31-bit value of the LCG in `rng` (a volatile)."
  [rng]
  (vswap! rng (fn [x]
                #?(:clj (bit-and (+ (unchecked-int (* (long x) 1103515245)) 12345) 0x7fffffff)
                   :cljs (bit-and (+ (js/Math.imul x 1103515245) 12345) 0x7fffffff)))))

(defn- below
  "An int in 0..n-1 from the high bits: floor(x / 2^31 * n) for the next draw
   x, or, when n is over 2^31 (`u32`), x the next two draws as 62 bits, over
   2^62. The same double arithmetic on both hosts, so they agree."
  [rng n]
  ;; `double` here is this namespace's generator: (* 1.0 x) is the cast
  (let [x (* 1.0 (next-int! rng))
        f (if (<= n 2147483648)
            (/ x 2147483648.0)
            (/ (+ (* x 2147483648.0) (next-int! rng)) 4611686018427387904.0))
        i (Math/floor (* f n))]
    #?(:clj (min (long i) (dec (long n))) :cljs (min i (dec n)))))

(defn choose
  "An int in lo..hi, inclusive."
  [lo hi]
  (fn [rng] (+ lo (below rng (inc (- hi lo))))))

(defn return
  [x]
  (fn [_] x))

(defn elements
  [coll]
  (let [v (vec coll)]
    (fn [rng] (nth v (below rng (count v))))))

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
  (let [total (reduce + (clojure.core/map first pairs))]
    (fn [rng]
      (loop [r (below rng total) [[w g] & more] pairs]
        (if (< r w) (g rng) (recur (- r w) more))))))

(defn bind
  [g f]
  (fn [rng] ((f (g rng)) rng)))

(defn fmap
  [f g]
  (fn [rng] (f (g rng))))

(defn one-of
  [gs]
  (let [v (vec gs)]
    (fn [rng] ((nth v (below rng (count v))) rng))))

(def boolean
  (fn [rng] (zero? (below rng 2))))

(def small-integer
  "-200..200, as test.check at its largest size."
  (choose -200 200))

(def large-integer
  "Within 2^31 either side of 0."
  (fn [rng] (let [m (next-int! rng)] (if (zero? (below rng 2)) m (- m)))))

(defn large-integer*
  "At least :min (and within 2^31)."
  [{:keys [min]}]
  (fn [rng] (+ min (next-int! rng))))

(defn double*
  "A double in :min..:max (never infinite or NaN)."
  [{:keys [min max]}]
  (fn [rng] (+ min (* (- max min) (/ (next-int! rng) 2147483647.0)))))

(def double
  (fn [rng] (/ ((choose -1000000000 1000000000) rng) 1000.0)))

(def char
  "A character 0..255, as test.check's `char`."
  (fmap #?(:clj clojure.core/char :cljs #(.fromCharCode js/String %)) (choose 0 255)))

(def byte
  "A signed byte, -128..127."
  (choose -128 127))

(def string-alphanumeric
  "0 to 20 characters from a-z, A-Z, and 0-9."
  (let [cs "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    (fmap #(apply str %) (vector (elements cs) 0 20))))

(defn map
  "A map of draws of `kg` to draws of `vg`, at most :max-elements keys
   (fewer when a key repeats)."
  [kg vg {:keys [max-elements] :or {max-elements 10}}]
  (fn [rng]
    (into {} (repeatedly ((choose 0 max-elements) rng) #(clojure.core/vector (kg rng) (vg rng))))))

(defn recursive-gen
  "`(container-fn inner)` nested at most 3 deep over `scalar-g`: at each
   level a scalar one time in three, and always at the deepest."
  [container-fn scalar-g]
  (letfn [(at [depth]
            (if (= depth 3)
              scalar-g
              (frequency [[1 scalar-g]
                          [2 (fn [rng] ((container-fn (at (inc depth))) rng))]])))]
    (at 0)))

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
