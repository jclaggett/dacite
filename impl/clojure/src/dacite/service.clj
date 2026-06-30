(ns dacite.service
  "Dacite service layer.

   Manages sessions, root pointers, and store access for authenticated users.
   Sits between the store layer and the transport layer (HTTP, etc.).

   The service maintains a single root hash pointing to a Dacite map
   of {username → user-tree}. Each user is delegated their subtree.
   User writes are assoc operations into the root map.

   A service instance holds:
   - A main store (persistent, shared across all users)
   - A single root hash (the service's entire state)
   - A user registry (user-id → password, for authentication)
   - Active sessions (session-token → session state)
   - An optional LMDB store for persisting the root hash

   Each session has:
   - A session store (ephemeral mem-store, acts as client proxy)
   - The authenticated user-id
   - The user's subtree root hash at session start

   Key operations:
   - create-service: initialize a new service with a main store
   - register-user: add a user with a password
   - login: authenticate and create a session (scoped to user's subtree)
   - session-get: read from main store (session token required)
   - session-put: push nodes to session store (client proxy)
   - update-root: declare new root, server walks session store to pull new nodes"
  (:require [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.core :as d])
  (:import [java.util UUID]))

;; =============================================================================
;; Root hash persistence
;; =============================================================================

(defn- load-root
  "Load the root hash from the LMDB meta db. Returns nil if not set."
  [lmdb-store]
  (when lmdb-store
    (store/lmdb-get-meta lmdb-store "root")))

(defn- save-root!
  "Persist the root hash to the LMDB meta db."
  [lmdb-store root-hash]
  (when lmdb-store
    (store/lmdb-put-meta! lmdb-store "root" root-hash)))

;; =============================================================================
;; Service creation
;; =============================================================================

(defn- resolve-user-root
  "Look up a user's subtree root hash from the service root map."
  [main-store root-hash user-id]
  (when root-hash
    (let [root-node (store/s-get main-store root-hash)]
      (when root-node
        ;; root-node is a Dacite map: look up user's key in the HAMT
        ;; We need to walk the map structure to find the user's value
        (store/bind-store main-store
                          (let [m (d/wrap-hash root-hash)]
                            (when-let [user-val (get m user-id)]
                              (types/dacite-hash user-val))))))))

(defn create-service
  "Create a new Dacite service backed by the given store (or a fresh mem-store).
   Optionally pass an LMDB store for root hash persistence.
   Returns a service map (wrapped in an atom for mutability)."
  ([] (create-service (store/mem-store)))
  ([main-store] (create-service main-store nil))
  ([main-store lmdb-store]
   (let [root-hash (load-root lmdb-store)]
     (atom {:main-store main-store
            :lmdb-store lmdb-store
            :root-hash root-hash    ;; single root for entire service
            :users {}               ;; {user-id {:password password}}
            :sessions {}}))))

;; =============================================================================
;; User management
;; =============================================================================

(defn register-user
  "Register a user with a password. Returns the service."
  [service user-id password]
  (swap! service assoc-in [:users user-id] {:password password})
  service)

(defn get-root-hash
  "Get the service's single root hash."
  [service]
  (:root-hash @service))

(defn get-user-root
  "Get the current root hash for a user's subtree."
  [service user-id]
  (let [{:keys [main-store root-hash]} @service]
    (resolve-user-root main-store root-hash user-id)))

;; =============================================================================
;; Session management
;; =============================================================================

(defn login
  "Authenticate a user and create a session.
   Returns {:token t :root-hash h} on success, nil on failure.
   The root-hash is the user's subtree root (delegated access)."
  [service user-id password]
  (let [{:keys [users]} @service
        user (get users user-id)]
    (when (and user (= password (:password user)))
      (let [user-root (get-user-root service user-id)
            token (str (UUID/randomUUID))
            session {:user-id user-id
                     :session-store (store/mem-store)
                     :root-hash user-root}]
        (swap! service assoc-in [:sessions token] session)
        {:token token :root-hash user-root}))))

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
;; Read: session-authorized main store access
;; =============================================================================

(defn session-get
  "Read a node from the main store. Authorized by session token only."
  [service token target-hash]
  (let [session (get-session service token)]
    (if (nil? session)
      {:error :invalid-session}
      (let [main-store (:main-store @service)]
        {:value (store/s-get main-store target-hash)}))))

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
   into the main store. Returns {:ok true :nodes-pulled n} or {:error ...}."
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
  "Declare a new user subtree root hash. The server walks from new-root
   through the session store (proxy), pulling new nodes into the main store.
   On success, assocs the user's new subtree into the service root map
   and persists the new service root hash."
  [service token new-user-root]
  (let [session (get-session service token)]
    (cond
      (nil? session)
      {:error :invalid-session}

      :else
      (let [main-store (:main-store @service)
            session-store (:session-store session)
            result (walk-and-pull main-store session-store new-user-root)]
        (if (:error result)
          result
          (let [user-id (:user-id session)
                ;; Assoc user's new subtree into the service root map
                old-root (:root-hash @service)
                new-service-root
                (store/bind-store main-store
                                  (let [root-map (if old-root
                                                   (d/wrap-hash old-root)
                                                   (d/hash-map))
                                        user-val (d/wrap-hash new-user-root)
                                        new-map (assoc root-map user-id user-val)]
                                    (types/dacite-hash new-map)))]
            ;; Update service root and session
            (swap! service (fn [s]
                             (-> s
                                 (assoc :root-hash new-service-root)
                                 (assoc-in [:sessions token :root-hash] new-user-root))))
            ;; Persist to LMDB meta db
            (save-root! (:lmdb-store @service) new-service-root)
            (assoc result :root-hash new-user-root)))))))
