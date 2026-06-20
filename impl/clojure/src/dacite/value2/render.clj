(ns dacite.value2.render
  "Bounded rendering for Dacite values: safe `toString` and REPL printing.

   `toString` never throws and never force-realizes a whole collection. It
   walks at most `*to-string-element-limit*` elements (default 32) or
   `*to-string-char-limit*` characters (default 64), fetching only that
   prefix from the store. Nested elements are rendered recursively with
   the same bounds.

   `print-dacite-value` (via `print-method`) honors `*print-length*` and
   `*print-level*` for REPL output."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value2.types :as types]
            [dacite.value2.finger-tree :as ft])
  (:import [java.io Writer]))

(declare print-dacite-value)

;; =============================================================================
;; Defaults (always-on bounds for Object/toString)
;; =============================================================================

(def ^:dynamic *to-string-element-limit*
  "Maximum collection elements rendered by `bounded-to-string`."
  32)

(def ^:dynamic *to-string-char-limit*
  "Maximum characters rendered from a Dacite string by `bounded-to-string`."
  64)

(def ^:dynamic *to-string-byte-hex-limit*
  "Maximum bytes shown as hex in a blob summary."
  16)

;; =============================================================================
;; Internal helpers
;; =============================================================================

(declare bounded-to-string)

(defn- node-root [store h]
  (:root (types/entry-data (store/s-get store h))))

(defn- render-scalar [v]
  (pr-str (types/realize v)))

(defn- render-string [v]
  (let [store (types/dacite-store v)
        h (types/dacite-hash v)
        n (count v)
        limit *to-string-char-limit*
        root (node-root store h)]
    (cond
      (zero? n) ""
      (<= n limit)
      (apply clojure.core/str
             (map #(types/entry-data (store/s-get store %))
                  (take n (ft/ft-seq store root))))
      :else
      (let [prefix (apply clojure.core/str
                          (map #(types/entry-data (store/s-get store %))
                               (take limit (ft/ft-seq store root))))]
        (str "\"" prefix "…\" (" n " chars)")))))

(defn- render-blob [v]
  (let [store (types/dacite-store v)
        h (types/dacite-hash v)
        n (count v)
        hex-limit *to-string-byte-hex-limit*
        root (node-root store h)
        truncated? (> n hex-limit)
        refs (take hex-limit (ft/ft-seq store root))
        hex (str/join " "
                      (map #(format "%02x" (types/entry-data (store/s-get store %)))
                           refs))]
    (str "<blob " n " bytes"
         (when (seq refs) (str " 0x" hex (when truncated? " …")))
         ">")))

(defn- render-seq-coll
  [v open close join-fn]
  (let [n (count v)
        limit *to-string-element-limit*
        parts (mapv bounded-to-string (take limit (or (seq v) ())))
        body (join-fn parts)]
    (str open body (when (> n limit) (str " … (" n " total)")) close)))

(defn- render-vector [v]
  (render-seq-coll v "[" "]" #(str/join " " %)))

(defn- render-set [v]
  (render-seq-coll v "#{" "}" #(str/join " " %)))

(defn- render-map [v]
  (let [n (count v)
        limit *to-string-element-limit*
        entries (take limit (or (seq v) ()))
        parts (mapv (fn [e]
                      (str (bounded-to-string (key e)) " "
                           (bounded-to-string (val e))))
                    entries)
        body (str/join ", " parts)]
    (str "{" body (when (> n limit) (str " … (" n " total)")) "}")))

;; =============================================================================
;; Bounded toString
;; =============================================================================

(defn bounded-to-string
  "Render a Dacite value as a bounded, never-throwing debug string."
  [v]
  (case (types/dacite-type v)
    "vector" (render-vector v)
    "set"    (render-set v)
    "map"    (render-map v)
    "string" (render-string v)
    "blob"   (render-blob v)
    (render-scalar v)))

;; =============================================================================
;; REPL printing (*print-length* / *print-level*)
;; =============================================================================

(defn- print-element [el ^Writer w level length]
  (if (satisfies? types/IDaciteValue el)
    (binding [*print-level* (when level (dec level))]
      (print-dacite-value el w))
    (print el w)))

(defn- print-items [items print-one ^Writer w level length]
  (loop [s (seq items) i 0]
    (when s
      (if (and length (>= i length))
        (do
          (when (pos? i) (.write w " "))
          (.write w "#"))
        (do
          (when (pos? i) (.write w " "))
          (print-one (first s) w level length)
          (recur (next s) (inc i)))))))

(defn- print-vector [v ^Writer w level length]
  (.write w "[")
  (print-items (or (seq v) ()) print-element w level length)
  (.write w "]"))

(defn- print-set [v ^Writer w level length]
  (.write w "#{")
  (print-items (or (seq v) ()) print-element w level length)
  (.write w "}"))

(defn- print-map [v ^Writer w level length]
  (.write w "{")
  (print-items (or (seq v) ())
               (fn [e ^Writer w level length]
                 (print-element (key e) w level length)
                 (.write w " ")
                 (print-element (val e) w level length))
               w level length)
  (.write w "}"))

(defn- print-string [v ^Writer w _level _length]
  ;; Match Clojure: strings print via pr (quoted, escaped).
  (binding [*out* w]
    (pr (bounded-to-string v))))

(defn- print-blob [v ^Writer w _level _length]
  (.write w (bounded-to-string v)))

(defn- print-scalar [v ^Writer w]
  (binding [*out* w]
    (pr (types/realize v))))

(defn print-dacite-value
  "Write a Dacite value to a `Writer`, honoring `*print-length*` and
   `*print-level*`."
  [v ^Writer w]
  (let [level *print-level*]
    (if (and level (<= level 0))
      (.write w "#")
      (case (types/dacite-type v)
        "vector" (print-vector v w level *print-length*)
        "set"    (print-set v w level *print-length*)
        "map"    (print-map v w level *print-length*)
        "string" (print-string v w level *print-length*)
        "blob"   (print-blob v w level *print-length*)
        (print-scalar v w)))))
