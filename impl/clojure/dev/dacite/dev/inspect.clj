(ns dacite.dev.inspect
  "Introspection tooling for Clojure collection interfaces.

   Answers the question: what interfaces and methods must a Dacite
   collection implement to behave like a native Clojure vector or map?

   Usage at REPL:
     (require '[dacite.dev.inspect :as i])
     (i/interfaces-of (class []))
     (i/methods-of clojure.lang.IPersistentVector)
     (i/report {:class (class [])})

   Usage from CLI:
     clj -X:dev dacite.dev.inspect/report :class clojure.lang.PersistentVector
     clj -X:dev dacite.dev.inspect/report :class clojure.lang.PersistentHashMap
     clj -X:dev dacite.dev.inspect/report :class clojure.lang.PersistentVector :verbose true"
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; =============================================================================
;; Core introspection
;; =============================================================================

(defn interfaces-of
  "Return all interfaces implemented by a class, sorted by name.
   Includes transitive interfaces (supers)."
  [^Class cls]
  (->> (supers cls)
       (filter #(.isInterface ^Class %))
       (sort-by #(.getName ^Class %))
       vec))

(defn abstract-classes-of
  "Return abstract superclasses (often carry important method impls)."
  [^Class cls]
  (->> (supers cls)
       (remove #(.isInterface ^Class %))
       (filter #(java.lang.reflect.Modifier/isAbstract (.getModifiers ^Class %)))
       (sort-by #(.getName ^Class %))
       vec))

(defn methods-of
  "Return method signatures declared by an interface or class.
   Each entry is a map with :name, :return, :params, and :declaring-class."
  [^Class iface]
  (->> (.getMethods iface)
       (filter #(= (.getDeclaringClass ^java.lang.reflect.Method %) iface))
       (map (fn [^java.lang.reflect.Method m]
              {:name       (symbol (.getName m))
               :return     (.getSimpleName (.getReturnType m))
               :params     (mapv #(.getSimpleName ^Class %) (.getParameterTypes m))
               :declaring  (.getSimpleName (.getDeclaringClass m))}))
       (sort-by (juxt :name (comp count :params)))
       vec))

(defn all-methods
  "Return all methods from all interfaces of a class, grouped by interface."
  [^Class cls]
  (->> (interfaces-of cls)
       (map (fn [^Class iface]
              [(.getName iface) (methods-of iface)]))
       (filter (fn [[_ methods]] (seq methods)))
       (into (sorted-map))))

;; =============================================================================
;; Clojure protocol detection
;; =============================================================================

(defn protocols-of
  "Detect which common Clojure protocols/interfaces a class satisfies.
   Uses isa? on the class directly, which covers all core protocols
   that have backing Java interfaces."
  [^Class cls]
  (let [checks (cond-> []
                 (isa? cls clojure.lang.Seqable)     (conj 'Seqable)
                 (isa? cls clojure.lang.Counted)      (conj 'Counted)
                 (isa? cls clojure.lang.Indexed)       (conj 'Indexed)
                 (isa? cls clojure.lang.ILookup)       (conj 'ILookup)
                 (isa? cls clojure.lang.Associative)   (conj 'Associative)
                 (isa? cls clojure.lang.Reversible)    (conj 'Reversible)
                 (isa? cls clojure.lang.IReduce)       (conj 'IReduce)
                 (isa? cls clojure.lang.IReduceInit)   (conj 'IReduceInit)
                 (isa? cls clojure.lang.IKVReduce)     (conj 'IKVReduce)
                 (isa? cls clojure.lang.IFn)           (conj 'IFn)
                 (isa? cls clojure.lang.IObj)          (conj 'IObj)
                 (isa? cls clojure.lang.IMeta)         (conj 'IMeta)
                 (isa? cls clojure.lang.IHashEq)       (conj 'IHashEq)
                 (isa? cls clojure.lang.IPersistentCollection) (conj 'IPersistentCollection)
                 (isa? cls clojure.lang.Sequential)    (conj 'Sequential)
                 (isa? cls java.lang.Iterable)         (conj 'Iterable)
                 (isa? cls java.io.Serializable)       (conj 'Serializable)
                 (isa? cls java.lang.Comparable)       (conj 'Comparable)
                 (isa? cls java.util.List)             (conj 'java.util.List)
                 (isa? cls java.util.RandomAccess)     (conj 'java.util.RandomAccess)
                 (isa? cls java.util.Map)              (conj 'java.util.Map)
                 (isa? cls java.util.Set)              (conj 'java.util.Set))]
    checks))

;; =============================================================================
;; Updater detection (à la deltype)
;; =============================================================================

(def ^:private known-updaters
  "Methods that return a new instance of the collection (not void/boolean/etc).
   Based on deltype's updater? multimethod, extended for completeness."
  #{'assoc 'assocN 'assocEx 'without 'cons 'empty 'pop 'conj 'disjoin 'withMeta})

(defn updater?
  "Is this method an 'updater' — i.e., returns a new collection of the same type?"
  [{:keys [name]}]
  (contains? known-updaters name))

;; =============================================================================
;; Reporting
;; =============================================================================

(defn- format-method [{:keys [name return params] :as m}]
  (let [tag (if (updater? m) " [updater]" "")]
    (format "    %-20s (%s) → %s%s"
            name
            (str/join ", " params)
            return
            tag)))

(defn- print-interface-report [^Class cls verbose?]
  (let [iface-methods (all-methods cls)
        ifaces (interfaces-of cls)
        abstracts (abstract-classes-of cls)]

    (println (str "\n═══ " (.getName cls) " ═══"))
    (println (format "  Interfaces: %d" (count ifaces)))
    (println (format "  Abstract superclasses: %d" (count abstracts)))

    (println "\n── Interfaces with declared methods ──")
    (doseq [[iface-name methods] iface-methods]
      (println (format "\n  %s (%d methods)" iface-name (count methods)))
      (doseq [m methods]
        (println (format-method m))))

    (when verbose?
      (println "\n── Marker interfaces (no methods) ──")
      (let [markers (->> ifaces
                         (filter #(empty? (methods-of %)))
                         (map #(.getName ^Class %)))]
        (doseq [m markers]
          (println (str "  " m))))

      (println "\n── Abstract superclasses ──")
      (doseq [^Class a abstracts]
        (println (str "  " (.getName a)))))

    (println "\n── Summary: updater methods ──")
    (let [all-m (mapcat val iface-methods)
          updaters (filter updater? all-m)]
      (doseq [m updaters]
        (println (format "  %-20s from %s" (:name m) (:declaring m)))))

    (println)))

(defn report
  "Print a full interface report for a Clojure collection class.

   CLI:  clj -X:dev dacite.dev.inspect/report :class clojure.lang.PersistentVector
   REPL: (report {:class (class [])})"
  [{:keys [class verbose]
    :or {verbose false}}]
  (let [cls (cond
              (class? class) class
              (symbol? class) (resolve class)
              (string? class) (Class/forName class)
              :else (throw (ex-info "Expected a class, symbol, or string"
                                    {:got class})))]
    (print-interface-report cls (boolean verbose))))

;; =============================================================================
;; Comparison helper
;; =============================================================================

(defn compare-interfaces
  "Show which interfaces two classes share and which are unique to each.

   CLI:  clj -X:dev dacite.dev.inspect/compare-interfaces :a clojure.lang.PersistentVector :b clojure.lang.PersistentHashMap"
  [{:keys [a b]}]
  (let [resolve-cls (fn [x]
                      (cond
                        (class? x) x
                        (symbol? x) (resolve x)
                        (string? x) (Class/forName x)
                        :else (throw (ex-info "Expected class/symbol/string" {:got x}))))
        cls-a (resolve-cls a)
        cls-b (resolve-cls b)
        ifaces-a (set (map #(.getName ^Class %) (interfaces-of cls-a)))
        ifaces-b (set (map #(.getName ^Class %) (interfaces-of cls-b)))
        shared (set/intersection ifaces-a ifaces-b)
        only-a (set/difference ifaces-a ifaces-b)
        only-b (set/difference ifaces-b ifaces-a)]

    (println (format "\n═══ Comparing %s vs %s ═══"
                     (.getSimpleName cls-a)
                     (.getSimpleName cls-b)))

    (println (format "\n── Shared (%d) ──" (count shared)))
    (doseq [i (sort shared)] (println (str "  " i)))

    (println (format "\n── Only in %s (%d) ──" (.getSimpleName cls-a) (count only-a)))
    (doseq [i (sort only-a)] (println (str "  " i)))

    (println (format "\n── Only in %s (%d) ──" (.getSimpleName cls-b) (count only-b)))
    (doseq [i (sort only-b)] (println (str "  " i)))

    (println)))

(comment
  ;; REPL examples
  (interfaces-of (class []))
  (methods-of clojure.lang.IPersistentVector)
  (report {:class (class [])})
  (report {:class (class {}) :verbose true})
  (compare-interfaces {:a (class []) :b (class {})}))
