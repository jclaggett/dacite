(ns dacite.store.remote-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [dacite.store.remote :as remote]
            [dacite.store :as store]
            [dacite.store.lru :as lru])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(def ^:dynamic *server* nil)
(def ^:dynamic *base-url* nil)

(defn- send-edn [^HttpExchange ex status body]
  (let [bytes (.getBytes (pr-str body) "UTF-8")]
    (.getResponseHeaders ex)
    (.sendResponseHeaders ex status (count bytes))
    (with-open [os (.getResponseBody ex)]
      (.write os bytes))))

(defn- read-edn-body [^HttpExchange ex]
  (edn/read-string (String. (.readAllBytes (.getRequestBody ex)) "UTF-8")))

(defn start-test-server!
  "Start a minimal service.md-compatible server backed by store atom."
  []
  (let [state (atom {:content (store/mem-store)
                     :root nil})
        server (HttpServer/create (InetSocketAddress. 0) 0)
        port (.. server getAddress getPort)
        base-url (str "http://localhost:" port)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (let [method (.getRequestMethod exchange)
               path (.getPath (.getRequestURI exchange))]
           (try
             (cond
               (and (= "GET" method) (= path "/root"))
               (send-edn exchange 200 {:root (when-let [r (:root @state)]
                                               (store/hash->hex r))})

               (and (= "POST" method) (= path "/root/cas"))
               (let [{:keys [expected new]} (read-edn-body exchange)
                     exp (when expected (store/hex->hash expected))
                     new-h (store/hex->hash new)]
                 (if (= exp (:root @state))
                   (do (swap! state assoc :root new-h)
                       (send-edn exchange 200 {:ok true}))
                   (send-edn exchange 409 {:ok false})))

               (and (= "GET" method) (.startsWith path "/node/"))
               (let [hex (subs path 6)
                     h (store/hex->hash hex)
                     node (store/s-get (:content @state) h)]
                 (if node
                   (let [bytes (.getBytes (pr-str node) "UTF-8")]
                     (.sendResponseHeaders exchange 200 (count bytes))
                     (with-open [os (.getResponseBody exchange)]
                       (.write os bytes)))
                   (.sendResponseHeaders exchange 404 -1)))

               (and (= "PUT" method) (.startsWith path "/node/"))
               (let [hex (subs path 6)
                     h (store/hex->hash hex)
                     node (read-edn-body exchange)]
                 (swap! state update :content store/s-put h node)
                 (.sendResponseHeaders exchange 204 -1))

               (and (= "HEAD" method) (.startsWith path "/node/"))
               (let [hex (subs path 6)
                     h (store/hex->hash hex)]
                 (if (store/s-has? (:content @state) h)
                   (.sendResponseHeaders exchange 200 -1)
                   (.sendResponseHeaders exchange 404 -1)))

               (and (= "DELETE" method) (.startsWith path "/node/"))
               (let [hex (subs path 6)
                     h (store/hex->hash hex)]
                 (swap! state update :content store/s-delete h)
                 (.sendResponseHeaders exchange 204 -1))

               :else
               (.sendResponseHeaders exchange 404 -1))
             (catch Exception e
               (send-edn exchange 500 {:error (.getMessage e)})))))))
    (.setExecutor server nil)
    (.start server)
    {:server server :base-url base-url :state state}))

(defn server-fixture [f]
  (let [{:keys [server base-url]} (start-test-server!)]
    (binding [*server* server
              *base-url* base-url]
      (try
        (f)
        (finally
          (.stop server 0))))))

(use-fixtures :each server-fixture)

(deftest remote-get-put-test
  (testing "round-trip node over HTTP"
    (let [r (remote/remote-store *base-url*)
          h [1 2 3 4]
          node ["i64" 42]]
      (store/s-put r h node)
      (is (= node (store/s-get r h)))
      (is (store/s-has? r h)))))

(deftest remote-cas-root-test
  (testing "CAS root over HTTP"
    (let [r (remote/remote-store *base-url*)
          h1 [1 0 0 0]
          h2 [2 0 0 0]]
      (is (true? (remote/remote-cas-root! r nil h1)))
      (is (= h1 (remote/remote-get-root r)))
      (is (false? (remote/remote-cas-root! r nil h2)))
      (is (true? (remote/remote-cas-root! r h1 h2)))
      (is (= h2 (remote/remote-get-root r))))))

(deftest layered-lru-remote-test
  (testing "LRU cache read-through from remote layer"
    (let [remote-s (remote/remote-store *base-url*)
          cache (lru/lru-store 10)
          layered (store/layered-store cache remote-s)
          h [9 9 9 9]
          node ["i64" 7]]
      (store/s-put remote-s h node)
      (is (not (store/s-has? cache h)))
      (is (= node (store/s-get layered h)))
      (is (= node (store/s-get cache h))))))
