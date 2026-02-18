(ns dacite.dev.inspect-test
  "Tests for the collection interface introspection tool.

   These tests exercise all public functions and double as usage examples.
   Since this is dev tooling, we verify structure and exercised code paths
   rather than exact output values."
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.dev.inspect :as inspect]))

;; =============================================================================
;; interfaces-of
;; =============================================================================

(deftest interfaces-of-vector
  (testing "PersistentVector implements expected key interfaces"
    (let [ifaces (inspect/interfaces-of clojure.lang.PersistentVector)
          iface-names (set (map #(.getName ^Class %) ifaces))]
      (is (vector? ifaces))
      (is (pos? (count ifaces)))
      (is (contains? iface-names "clojure.lang.IPersistentVector"))
      (is (contains? iface-names "clojure.lang.Indexed"))
      (is (contains? iface-names "clojure.lang.Sequential"))
      (is (contains? iface-names "clojure.lang.ILookup")))))

(deftest interfaces-of-map
  (testing "PersistentHashMap implements expected key interfaces"
    (let [ifaces (inspect/interfaces-of clojure.lang.PersistentHashMap)
          iface-names (set (map #(.getName ^Class %) ifaces))]
      (is (vector? ifaces))
      (is (pos? (count ifaces)))
      (is (contains? iface-names "clojure.lang.IPersistentMap"))
      (is (contains? iface-names "clojure.lang.ILookup"))
      (is (contains? iface-names "java.util.Map")))))

(deftest interfaces-of-sorted
  (testing "Results are sorted by interface name"
    (let [ifaces (inspect/interfaces-of clojure.lang.PersistentVector)
          names (map #(.getName ^Class %) ifaces)]
      (is (= names (sort names))))))

;; =============================================================================
;; abstract-classes-of
;; =============================================================================

(deftest abstract-classes-of-vector
  (testing "PersistentVector has abstract superclasses"
    (let [abstracts (inspect/abstract-classes-of clojure.lang.PersistentVector)]
      (is (vector? abstracts))
      (is (pos? (count abstracts)))
      (is (every? class? abstracts)))))

(deftest abstract-classes-of-map
  (testing "PersistentHashMap has abstract superclasses"
    (let [abstracts (inspect/abstract-classes-of clojure.lang.PersistentHashMap)]
      (is (vector? abstracts))
      (is (pos? (count abstracts))))))

;; =============================================================================
;; methods-of
;; =============================================================================

(deftest methods-of-persistent-vector
  (testing "IPersistentVector declares assocN, cons, length"
    (let [methods (inspect/methods-of clojure.lang.IPersistentVector)
          method-names (set (map :name methods))]
      (is (vector? methods))
      (is (pos? (count methods)))
      (is (contains? method-names 'assocN))
      (is (contains? method-names 'length)))))

(deftest methods-of-method-structure
  (testing "Each method has required keys"
    (let [methods (inspect/methods-of clojure.lang.IPersistentVector)]
      (doseq [m methods]
        (is (contains? m :name))
        (is (contains? m :return))
        (is (contains? m :params))
        (is (contains? m :declaring))
        (is (symbol? (:name m)))
        (is (string? (:return m)))
        (is (vector? (:params m)))
        (is (string? (:declaring m)))))))

(deftest methods-of-ilookup
  (testing "ILookup declares valAt with 1 and 2 params"
    (let [methods (inspect/methods-of clojure.lang.ILookup)
          valat-methods (filter #(= 'valAt (:name %)) methods)]
      (is (= 2 (count valat-methods)))
      (is (= #{1 2} (set (map #(count (:params %)) valat-methods)))))))

(deftest methods-of-marker-interface
  (testing "Marker interface (no declared methods) returns empty"
    (let [methods (inspect/methods-of clojure.lang.Sequential)]
      (is (empty? methods)))))

;; =============================================================================
;; all-methods
;; =============================================================================

(deftest all-methods-vector
  (testing "Groups methods by interface name"
    (let [grouped (inspect/all-methods clojure.lang.PersistentVector)]
      (is (map? grouped))
      (is (pos? (count grouped)))
      (is (contains? grouped "clojure.lang.IPersistentVector"))
      (is (contains? grouped "clojure.lang.ILookup"))
      ;; Marker interfaces with no methods are excluded
      (is (not (contains? grouped "clojure.lang.Sequential"))))))

;; =============================================================================
;; protocols-of
;; =============================================================================

(deftest protocols-of-vector
  (testing "PersistentVector satisfies expected protocols"
    (let [protos (inspect/protocols-of clojure.lang.PersistentVector)]
      (is (vector? protos))
      (is (pos? (count protos)))
      (is (some #{'Indexed} protos))
      (is (some #{'Sequential} protos))
      (is (some #{'IFn} protos))
      ;; Vectors are not maps
      (is (not (some #{'java.util.Map} protos))))))

(deftest protocols-of-map
  (testing "PersistentHashMap satisfies expected protocols"
    (let [protos (inspect/protocols-of clojure.lang.PersistentHashMap)]
      (is (some #{'java.util.Map} protos))
      (is (some #{'IKVReduce} protos))
      ;; Maps are not sequential
      (is (not (some #{'Sequential} protos))))))

(deftest protocols-of-non-collection
  (testing "A class with no collection interfaces returns empty"
    (let [protos (inspect/protocols-of String)]
      (is (vector? protos))
      (is (some #{'Comparable} protos))
      (is (not (some #{'IFn} protos))))))

;; =============================================================================
;; updater?
;; =============================================================================

(deftest updater-detection
  (testing "Known updater methods are detected"
    (is (true? (inspect/updater? {:name 'assoc})))
    (is (true? (inspect/updater? {:name 'cons})))
    (is (true? (inspect/updater? {:name 'without})))
    (is (true? (inspect/updater? {:name 'withMeta})))
    (is (true? (inspect/updater? {:name 'pop})))
    (is (true? (inspect/updater? {:name 'empty})))))

(deftest non-updater-detection
  (testing "Non-updater methods are not flagged"
    (is (false? (inspect/updater? {:name 'count})))
    (is (false? (inspect/updater? {:name 'seq})))
    (is (false? (inspect/updater? {:name 'valAt})))
    (is (false? (inspect/updater? {:name 'iterator})))))

;; =============================================================================
;; report (printing function — exercise all code paths)
;; =============================================================================

(deftest report-with-class-instance
  (testing "Report accepts a class instance"
    (let [output (with-out-str
                   (inspect/report {:class (class [])}))]
      (is (string? output))
      (is (pos? (count output))))))

(deftest report-with-symbol
  (testing "Report accepts a symbol"
    (let [output (with-out-str
                   (inspect/report {:class 'clojure.lang.PersistentHashMap}))]
      (is (string? output))
      (is (.contains output "PersistentHashMap")))))

(deftest report-with-string
  (testing "Report accepts a string class name"
    (let [output (with-out-str
                   (inspect/report {:class "clojure.lang.PersistentVector"}))]
      (is (.contains output "PersistentVector")))))

(deftest report-verbose-mode
  (testing "Verbose mode includes marker interfaces and abstract classes"
    (let [output (with-out-str
                   (inspect/report {:class (class []) :verbose true}))]
      (is (.contains output "Marker interfaces"))
      (is (.contains output "Abstract superclasses")))))

(deftest report-non-verbose-excludes-extras
  (testing "Non-verbose mode omits marker and abstract detail sections"
    (let [output (with-out-str
                   (inspect/report {:class (class [])}))]
      (is (not (.contains output "Marker interfaces")))
      ;; The summary line mentions abstract count; verbose adds the detail section
      (is (not (.contains output "── Abstract superclasses ──"))))))

(deftest report-invalid-class-throws
  (testing "Report throws on invalid input"
    (is (thrown? clojure.lang.ExceptionInfo
                 (inspect/report {:class 42})))))

;; =============================================================================
;; compare-interfaces
;; =============================================================================

(deftest compare-interfaces-vector-vs-map
  (testing "Comparison shows shared and unique interfaces"
    (let [output (with-out-str
                   (inspect/compare-interfaces
                    {:a clojure.lang.PersistentVector
                     :b clojure.lang.PersistentHashMap}))]
      (is (.contains output "Shared"))
      (is (.contains output "Only in PersistentVector"))
      (is (.contains output "Only in PersistentHashMap")))))

(deftest compare-interfaces-same-class
  (testing "Comparing a class to itself shows no unique interfaces"
    (let [output (with-out-str
                   (inspect/compare-interfaces
                    {:a (class []) :b (class [])}))]
      (is (.contains output "Shared"))
      (is (.contains output "Only in PersistentVector (0)")))))

(deftest compare-interfaces-accepts-symbols
  (testing "Comparison accepts symbols"
    (let [output (with-out-str
                   (inspect/compare-interfaces
                    {:a 'clojure.lang.PersistentVector
                     :b 'clojure.lang.PersistentHashMap}))]
      (is (.contains output "Comparing")))))

(deftest compare-interfaces-accepts-strings
  (testing "Comparison accepts string class names"
    (let [output (with-out-str
                   (inspect/compare-interfaces
                    {:a "clojure.lang.PersistentVector"
                     :b "clojure.lang.PersistentHashMap"}))]
      (is (.contains output "Comparing")))))
