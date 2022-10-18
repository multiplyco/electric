(ns contrib.datomic-contrib
  (:require [contrib.data :refer [index-by unqualify]]
            [contrib.datomic-m :as d]
            [hyperfiddle.photon :as p] ; ?
            [hyperfiddle.rcf :refer [tests % tap]]
            [missionary.core :as m]))

; These should use entity API & fetch data when necessary, doing on trees is not ergonomic enough
; (in Hyperfiddle-2020, much complexity stemmed from tree-passing, root cause batch data loading)
(defn identities "select available identifiers from datomic entity, in precedence order"
  [tree] (->> ((juxt :db/ident :db/id) tree)
              (remove nil?)))

(defn identify "infer canonical identity"
  ([tree fallback] (or (identify tree) fallback))
  ([tree] (first (identities tree))))

(tests
  (def tree {:db/id 35435060739965075 :db/ident :release.type/single :release.type/name "Single"})
  (identities tree) := [:release.type/single 35435060739965075]
  (identify tree) := :release.type/single

  (tests
    "these are bad; it should query for the canonical identity, not best locally available identity"
    (def tree2 {:db/id 35435060739965075 :release.type/name "Single"})
    (identities tree2) := [35435060739965075]
    (identify tree2) := 35435060739965075)

  "accept fallback value (like keywords)"
  (identify tree 0) := :release.type/single
  (identify tree2 0) := 35435060739965075
  (identify {} 0) := 0

  "No known identifier is valid"
  (identities {}) := []
  (identify {}) := nil

  (index-by :db/id [tree2])   := {35435060739965075 {:db/id 35435060739965075, :release.type/name "Single"}}
  (index-by identify [tree2]) := {35435060739965075 {:db/id 35435060739965075, :release.type/name "Single"}})

(defn reverse-attr [?kw]
  (if ?kw
    (keyword (namespace ?kw)
             (let [s (name ?kw)]
               (case (.charAt s 0)
                 \_ (subs s 1)
                 (str "_" s))))))

(tests
  (reverse-attr :foo/bar) := :foo/_bar
  (reverse-attr nil) := nil
  (reverse-attr :foo/_bar) := :foo/bar)

(defn attributes>
  ([db] (attributes> db [:db/ident]))
  ([db pull-pattern]
   (->> (d/qseq {:query '[:find (pull ?e pattern)
                          :in $ pattern
                          :where [?e :db/valueType _]]
                 :args [db pull-pattern]})
        (m/eduction (map first)))))

(tests
  (m/? (m/reduce conj [] (attributes> user/datomic-db [:db/ident])))
  (m/? (m/reduce conj [] (attributes> user/datomic-db)))
  (m/? (d/pull user/datomic-db {:eid 50 :selector [:db/ident :db/valueType :db/cardinality]})))

(defn schema! [db] ; todo stream
  (m/sp
    (let [>as (attributes> db [:db/ident
                               {:db/valueType [:db/ident]}
                               {:db/cardinality [:db/ident]}])

          ; :db/id is a renderable attr in the semantic UI, schema metadata describes how
          as (cons {:db/ident :db/id
                    :db/cardinality {:db/ident :db.cardinality/one}
                    :db/valueType {:db/ident :db.type/long}
                    #_#_:db/unique :db.unique/identity}
                   (m/? (m/reduce conj [] >as)))]

      ; todo - streaming group-by should be faster – shouldn't need to wait for all attrs to load
      ; todo - only load the attrs that the renderers actually needs
      (index-by :db/ident as))))

(defn schema> [db] (p/task->cp (schema! db)))

(tests
  (:db/ident (m/? (schema! user/datomic-db)))
  := #:db{:ident :db/ident,
          :valueType #:db{:ident :db.type/keyword},
          :cardinality #:db{:ident :db.cardinality/one}}

  (:db/id (m/? (schema! user/datomic-db)))
  := #:db{:ident :db/id,
          :cardinality #:db{:ident :db.cardinality/one},
          :valueType #:db{:ident :db.type/long}}

  (count (m/? (schema! user/datomic-db))))

(defn entity-history-datoms>
  ([db e] (entity-history-datoms> db e nil))
  ([db ?e ?a]
   (->> (m/ap
          (let [history (m/? (d/history db))
                ; (sequence #_(comp (xf-filter-before-date before)))
                >fwd-xs (d/datoms> history {:index :eavt :components [?e ?a]})
                >rev-xs (d/datoms> history {:index :vaet :components [?e ?a]})]
            (m/amb= (m/?> >fwd-xs) (m/?> >rev-xs)))))))

(comment
  (time (m/? (m/reduce conj [] (entity-history-datoms> user/db 74766790739005 nil))))
  (time (count (m/? (m/reduce conj [] (entity-history-datoms> user/db nil nil)))))
  (def it ((entity-history-datoms> user/db 74766790739005 nil)
           #(println ::notify) #(println ::terminate)))
  @it
  (it))

(defn ident! [db ?e]
  {:pre [db]}
  ; future work - cache idents?
  (m/sp
    (if ?e
      (let [xs (m/? (d/query {:query '[:find ?k :in $ ?e :where [?e :db/ident ?k]]
                              :args [db ?e]}))
            ; datomic onprem: #{[:db.excise/beforeT]}
            ; datomic cloud: [[:db.excise/beforeT]]
            x (ffirst xs)]
        (or x ?e)))))

(comment
  (m/? (d/query {:query '[:find (pull ?e [:db/ident]) ?f :in $ ?e
                          :where [?e :db/ident ?f]]
                 :args [user/db 17]}))

  (m/? (ident! user/db 17)) := :db.excise/beforeT
  (m/? (ident! user/db nil)) := nil)

;#?(:clj (defn before? [^java.util.Date a ^java.util.Date b] (<= (.getTime a) (.getTime b))))
;(defn- xf-filter-before-date [before] #?(:clj (filter (fn [[tx e a v added?]] (before? t before)))))

(defn sort-datoms-by-time
  [[tx  e  a  v  added]
   [tx' e' a' v' added']]
  ; tx are monotically increasing right?
  ; Draw :add as more recent than :retract for the same attribute
  (compare [tx' a added']
           [tx a' added]))

;(extend-protocol Datafiable
;  datomic.query.EntityMap
;  (datafy [o] (into {} o)))
;
;(extend-protocol Navigable
;  datomic.query.EntityMap
;  (nav [coll k v]
;    (clojure.datafy/datafy v)))

;#?(:clj (defn entity-datoms! [db e] (p/chan->task (d/datoms db {:index :eavt, :components [e]}))))
;#?(:clj (defn entity-datoms> [db e] (->> (p/chan->ap (d/datoms db {:index :eavt, :components [e]}))
;                                         (m/eduction (mapcat identity)))))
;#?(:clj (defn entity-datoms< [db a] (->> (entity-datoms> db a)
;                                         (m/reductions conj [])
;                                         (m/latest identity)))) ; FIXME BUFFER
;
;(comment
;  (m/? (entity-datoms! db 1))
;  (m/? (entity-datoms! db :db/ident))
;  (take 3 (m/? (m/reduce conj [] (entity-datoms> db :db/ident)))))