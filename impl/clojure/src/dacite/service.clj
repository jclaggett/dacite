(ns dacite.service
  "Dacite service layer.

   Manages sessions, root pointers, and proof chain verification.
   Sits between the store layer and the transport layer (HTTP, etc.).

   A service instance holds:
   - A main store (persistent, shared across all users)
   - A user registry (user-id → root-hash)
   - Active sessions (session-token → session state)

   Each session has:
   - A session store (ephemeral mem-store, acts as client proxy)
   - The authenticated user-id
   - The user's root hash at session start

   Key operations:
   - create-service: initialize a new service with a main store
   - register-user: add a user with an initial root hash
   - login: authenticate and create a session
   - session-get: read from main store with proof chain verification
   - session-put: push nodes to session store (client proxy)
   - update-root: declare new root, server walks session store to pull new nodes"
  (:require [dacite.store :as store]
            [dacite.auth :as auth]
            [dacite.types :as types])
  (:import [java.util UUID]))

;; =============================================================================
;; Service creation
;; =============================================================================

(defn create-service
  "Create a new Dacite service backed by the given store (or a fresh mem-store).
   Returns a service map (wrapped in an atom for mutability)."
  ([] (create-service (store/mem-store)))
  ([main-store]
   (atom {:main-store main-store
          :users {}        ;; {user-id {:password password :root-hash hash}}
          :sessions {}}))) ;; {token {:user-id id :session-store store :root-hash hash}}

;; =============================================================================
;; User management
;; =============================================================================

(defn register-user
  "Register a user with a password. Optionally provide an initial root hash.
   Returns the service."
  ([service user-id password]
   (register-user service user-id password nil))
  ([service user-id password root-hash]
   (swap! service assoc-in [:users user-id]
          {:password password :root-hash root-hash})
   service))

(defn get-root-hash
  "Get the current root hash for a user."
  [service user-id]
  (get-in @service [:users user-id :root-hash]))

;; =============================================================================
;; Session management
;; =============================================================================

(defn login
  "Authenticate a user and create a session.
   Returns {:token t :root-hash h} on success, nil on failure."
  [service user-id password]
  (let [{:keys [users]} @service
        user (get users user-id)]
    (when (and user (= password (:password user)))
      (let [token (str (UUID/randomUUID))
            session {:user-id user-id
                     :session-store (store/mem-store)
                     :root-hash (:root-hash user)}]
        (swap! service assoc-in [:sessions token] session)
        {:token token :root-hash (:root-hash user)}))))

(defn logout
  "Destroy a session."
  [service token]
  (swap! service update :sessions dissoc token)
  nil)

(defn- get-session
  "Look up a session by token. Returns nil if not found."
  [service token]
  (get-in @service [:sessions token]))

;; =============================================================================
;; Read: s-get with proof chain verification
;; =============================================================================

(defn session-get
  "Read a node from the main store, authorized by proof chain.
   The chain must start from the session's root hash.
   Returns the node value, or nil with an error."
  [service token target-hash chain]
  (let [session (get-session service token)]
    (cond
      (nil? session)
      {:error :invalid-session}

      (nil? chain)
      {:error :no-proof-chain}

      (not= (first chain) (:root-hash session))
      {:error :chain-root-mismatch}

      (not= (last chain) target-hash)
      {:error :chain-target-mismatch}

      :else
      (let [main-store (:main-store @service)]
        (if (auth/verify-proof-chain main-store chain)
          {:value (store/s-get main-store target-hash)}
          {:error :invalid-proof-chain})))))

;; =============================================================================
;; Session store: client proxy
;; =============================================================================

(defn session-put
  "Push a node to the session store (client proxy).
   No proof chain needed — session token is sufficient."
  [service token hash value]
  (let [session (get-session service token)]
    (cond
      (nil? session)
      {:error :invalid-session}

      :else
      (do (store/s-put (:session-store session) hash value)
          {:ok true}))))

(defn session-get-node
  "Read a node from the session store (proof chains, client-pushed data).
   Authorized by session token only."
  [service token hash]
  (let [session (get-session service token)]
    (cond
      (nil? session)
      {:error :invalid-session}

      :else
      {:value (store/s-get (:session-store session) hash)})))

;; =============================================================================
;; Write: root replacement with server-side walk
;; =============================================================================

(defn- walk-and-pull
  "Walk from new-root through the session store (proxy), pulling new nodes
   into the main store. Uses proof chains to verify each node.
   Returns {:ok true :nodes-pulled n} or {:error ...}."
  [main-store session-store new-root]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY new-root)
         visited #{}
         pulled 0]
    (if (empty? queue)
      {:ok true :nodes-pulled pulled}
      (let [h (peek queue)
            queue' (pop queue)]
        (if (visited h)
          (recur queue' visited pulled)
          (let [visited' (conj visited h)]
            (if (store/s-has? main-store h)
              ;; Already in main store — don't need to walk further
              (recur queue' visited' pulled)
              ;; Not in main store — must be in session store
              (if-let [node (store/s-get session-store h)]
                (do
                  (store/s-put main-store h node)
                  (let [children (types/child-hashes node)
                        new-children (remove visited' children)]
                    (recur (into queue' new-children)
                           visited'
                           (inc pulled))))
                ;; Node not found in either store
                {:error :missing-node :hash h}))))))))

(defn update-root
  "Declare a new root hash. The server walks from new-root through the
   session store (proxy), pulling new nodes into the main store.
   On success, updates the user's root pointer."
  [service token new-root]
  (let [session (get-session service token)]
    (cond
      (nil? session)
      {:error :invalid-session}

      :else
      (let [main-store (:main-store @service)
            session-store (:session-store session)
            result (walk-and-pull main-store session-store new-root)]
        (if (:error result)
          result
          (let [user-id (:user-id session)]
            ;; Update root in user registry and session
            (swap! service (fn [s]
                             (-> s
                                 (assoc-in [:users user-id :root-hash] new-root)
                                 (assoc-in [:sessions token :root-hash] new-root))))
            (assoc result :root-hash new-root)))))))
