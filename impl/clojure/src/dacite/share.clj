(ns dacite.share
  "Layer 5: Sharing conventions for Dacite.

   Sharing is pure convention atop Layer 4's proof-of-possession
   authorization. No new primitives — just root structure conventions:

     {:value   <app-data>
      :shares  {name → {:target Hash, :authorized Set|str}}
      :groups  {name → Set}}

   Authorization sets support:
   - Direct sets: #{:alice :bob}
   - Named groups: \"team\" → resolved via :groups map
   - Cofinite (public): A set containing ::public → authorizes everyone

   Session grants model:
     {:identity ID, :store IStore,
      :grants [{:hash Hash :authorized Set}]}
   Own root is just a grant where authorized = #{me}."
)

;; =============================================================================
;; Public sharing sentinel
;; =============================================================================

(def public
  "Sentinel value for public/cofinite authorization.
   A set containing this value authorizes everyone."
  ::public)

;; =============================================================================
;; Authorization helpers
;; =============================================================================

(defn resolve-set
  "Resolve an authorization value to a concrete set.
   If `auth` is a string, look it up in the groups map.
   Otherwise return it as-is (should be a set)."
  [auth groups]
  (if (string? auth)
    (get groups auth)
    auth))

(defn authorized?
  "Check if `id` is authorized for share `name` in `root`.
   Resolves named groups. Returns truthy if authorized, nil/false otherwise.
   A set containing `::public` authorizes any id."
  [root name id]
  (let [share-entry (get-in root [:shares name])
        auth-set (resolve-set (:authorized share-entry)
                              (get root :groups {}))]
    (when auth-set
      (or (contains? auth-set public)
          (contains? auth-set id)))))

(defn shares
  "Extract the shares map from a root. Returns {} if absent."
  [root]
  (get root :shares {}))

(defn groups
  "Extract the groups map from a root. Returns {} if absent."
  [root]
  (get root :groups {}))

;; =============================================================================
;; Session grants
;; =============================================================================

(defn make-grant
  "Create a grant entry."
  [hash authorized]
  {:hash hash :authorized authorized})

(defn own-root-grant
  "Create a grant for the session's own root."
  [root-hash identity]
  (make-grant root-hash #{identity}))

(defn grant-authorizes?
  "Check if a grant authorizes the given identity."
  [grant id]
  (let [auth (:authorized grant)]
    (or (contains? auth public)
        (contains? auth id))))

(defn find-authorized-grant
  "Find the first grant that authorizes `id` and whose hash matches
   the chain root. Returns the grant or nil."
  [grants id chain-root]
  (first (filter (fn [g]
                   (and (= (:hash g) chain-root)
                        (grant-authorizes? g id)))
                 grants)))

;; =============================================================================
;; Claim
;; =============================================================================

(defn claim
  "Attempt to claim a share from `sharer-root` (a map with :shares/:groups).
   If `id` is authorized for `share-name`, returns a grant
   {:hash target-hash :authorized auth-set}.
   Otherwise returns nil."
  [sharer-root share-name id]
  (let [share-entry (get-in sharer-root [:shares share-name])
        auth-set (resolve-set (:authorized share-entry)
                              (get sharer-root :groups {}))]
    (when (and auth-set
               (or (contains? auth-set public)
                   (contains? auth-set id)))
      (make-grant (:target share-entry) auth-set))))
