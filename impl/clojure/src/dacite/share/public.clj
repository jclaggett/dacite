(ns dacite.share.public
  "Public sharing and authorization for Dacite stores (Layer 5).

   Provides the ability to claim ownership of store roots,
   resolve authorization chains, and check authorization status.

   This is a skeleton — implementations will be added in Chapter 5.")

(defn claim
  "Claim ownership of a store root hash. Returns a claim token.
   Stub — not yet implemented."
  [_store _root-hash _identity]
  (throw (ex-info "dacite.share.public/claim not yet implemented" {})))

(defn resolve-authorized?
  "Resolve whether an identity is authorized to access a target hash
   by walking the authorization chain from root. Returns true/false.
   Stub — not yet implemented."
  [_store _root-hash _target-hash _identity]
  (throw (ex-info "dacite.share.public/resolve-authorized? not yet implemented" {})))

(defn authorized?
  "Check whether an identity holds a valid authorization for a target.
   Convenience wrapper around resolve-authorized?.
   Stub — not yet implemented."
  [_store _root-hash _target-hash _identity]
  (throw (ex-info "dacite.share.public/authorized? not yet implemented" {})))
