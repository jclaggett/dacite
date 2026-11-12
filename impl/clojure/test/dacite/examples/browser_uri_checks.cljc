(ns dacite.examples.browser-uri-checks
  "M6.4 URI rules. The host is a name. The fragment is a Dacite URN.
   Nothing here is an HTTP path. On the JVM and on nbb.

   These were the bodies of `dacite.examples.browser-uri-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [clojure.string :as str]
            [dacite.store.check :refer [is testing]]
            [dacite.examples.browser :as browser]))

(def hex-a (apply str (repeat 64 "a")))

(def hex-b (apply str (repeat 64 "b")))

(defn- uri [host hex]
  (str "dacite://" host "#urn:dacite:" hex))

(defn pasteable-form-is-the-fragment
  [_]
  (let [text (uri "shelf" hex-a)
        got (browser/parse-value-uri text "")]
    (is (= "shelf" (:host got)))
    (is (= hex-a (:hash got)))
    (is (= (str "urn:dacite:" hex-a) (:fragment got)))
    (is (= (subs text (inc (str/index-of text "#"))) (:fragment got)))
    (is (= :fragment (:from got)))
    (is (nil? (:http-path got)))
    (is (not (str/includes? (:host got) "://")))))

(defn host-may-be-a-dotted-name
  [_]
  (let [got (browser/parse-value-uri (uri "dacite.io" hex-a) "")]
    (is (= "dacite.io" (:host got)))
    (is (= hex-a (:hash got)))
    (is (nil? (:http-path got)))))

(defn field-accepts-the-peer-together-with-a-urn
  [_]
  (testing "whitespace in the one field"
    (let [got (browser/parse-value-uri
               (str "dacite://shelf urn:dacite:" hex-b) "")]
      (is (= "shelf" (:host got)))
      (is (= hex-b (:hash got)))
      (is (= :urn (:from got)))
      (is (nil? (:http-path got)))))
  (testing "a separate URN when the peer URI has no fragment"
    (let [got (browser/parse-value-uri
               "dacite://shelf" (str "urn:dacite:" hex-b))]
      (is (= "shelf" (:host got)))
      (is (= hex-b (:hash got)))
      (is (= :urn (:from got)))
      (is (nil? (:http-path got)))))
  (testing "the same digest in the fragment and the URN"
    (let [got (browser/parse-value-uri (uri "shelf" hex-a)
                                       (str "urn:dacite:" hex-a))]
      (is (= hex-a (:hash got)))
      (is (= :fragment (:from got)))))
  (testing "a peer name alone is not a value"
    (let [got (browser/parse-value-uri "dacite://shelf" "")]
      (is (= "shelf" (:host got)))
      (is (nil? (:hash got)))
      (is (nil? (:fragment got)))
      (is (nil? (:http-path got))))))

(defn rejects-an-algorithm-token-and-a-path
  [_]
  (is (= :algorithm
         (:error (browser/parse-value-uri
                  (str "dacite://shelf#urn:dacite:sha256:" hex-a) ""))))
  (is (= :algorithm
         (:error (browser/parse-value-uri
                  "dacite://shelf" (str "urn:sha256:" hex-a)))))
  (is (= :has-path
         (:error (browser/parse-value-uri
                  (str "dacite://shelf/node/" hex-a) ""))))
  (is (= :has-path
         (:error (browser/parse-value-uri
                  (str "dacite://dacite.io/" hex-a) ""))))
  (is (= :has-port
         (:error (browser/parse-value-uri "dacite://shelf:8787" ""))))
  (is (= :has-user
         (:error (browser/parse-value-uri "dacite://op@shelf" ""))))
  (is (= :has-query
         (:error (browser/parse-value-uri "dacite://shelf?room=1" ""))))
  (is (= :not-dacite
         (:error (browser/parse-value-uri
                  (str "https://dacite.io/node/" hex-a) ""))))
  (is (= :bad-urn
         (:error (browser/parse-value-uri (str "dacite://shelf#" hex-a) ""))))
  (is (= :bad-urn
         (:error (browser/parse-value-uri
                  (str "dacite://shelf#urn:dacite:" (subs hex-a 0 63)) ""))))
  (is (= :bad-urn
         (:error (browser/parse-value-uri
                  (str "dacite://shelf#urn:dacite:" (str/upper-case hex-a)) ""))))
  (is (= :mismatch
         (:error (browser/parse-value-uri
                  (uri "shelf" hex-a) (str "urn:dacite:" hex-b)))))
  (is (= :empty (:error (browser/parse-value-uri "" ""))))
  (is (= :empty (:error (browser/parse-value-uri "   " nil)))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"pasteable-form-is-the-fragment" pasteable-form-is-the-fragment
   "host-may-be-a-dotted-name" host-may-be-a-dotted-name
   "field-accepts-the-peer-together-with-a-urn" field-accepts-the-peer-together-with-a-urn
   "rejects-an-algorithm-token-and-a-path" rejects-an-algorithm-token-and-a-path})
