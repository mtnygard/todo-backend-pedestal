(ns todo-backend.service
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [datomic.api :as d]
            [io.pedestal.http :as bootstrap]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition :as definition]
            [io.pedestal.interceptor :as interceptor]
            [ring.util.response :as ring-resp]
            [io.pedestal.interceptor.helpers :as helpers]))

(def schema [{:db/id                 #db/id[:db.part/db]
              :db/ident              :todo/title
              :db/valueType          :db.type/string
              :db/cardinality        :db.cardinality/one
              :db/doc                "The title of a single TODO item"
              :db.install/_attribute :db.part/db}
             {:db/id                 #db/id[:db.part/db]
              :db/ident              :todo/completed
              :db/valueType          :db.type/boolean
              :db/cardinality        :db.cardinality/one
              :db/doc                "A flag. True when the TODO item is completed"
              :db.install/_attribute :db.part/db}
             {:db/id                 #db/id[:db.part/db]
              :db/ident              :todo/order
              :db/valueType          :db.type/long
              :db/cardinality        :db.cardinality/one
              :db/doc                "The position this item should appear in sequence"
              :db.install/_attribute :db.part/db}])

(defn ensure-schema
  [uri]
  (d/create-database uri)
  @(d/transact (d/connect uri) schema))

(defn insert-datomic
  [conn]
  "Provide a Datomic conn and db in all incoming requests"
  (interceptor/interceptor
   {:name ::insert-datomic
    :enter (fn [context]
             (-> context
                 (assoc-in [:request :conn] conn)
                 (assoc-in [:request :db] (d/db conn))))}))

(defn resolve-id
  [tid tx-result]
  (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) tid))

(defn title-datom [id title]
  (when title
    [:db/add id :todo/title title]))

(defn completed-datom [id completed]
  (when (not (nil? completed))
    [:db/add id :todo/completed completed]))

(defn order-datom [id order]
  (when (not (nil? order))
    [:db/add id :todo/order order]))

(defn make-todo-datoms
  [id title completed order]
  (let [id (or id (d/tempid :db.part/user))]
    (keep identity
          [(title-datom id title)
           (completed-datom id completed)
           (order-datom id order)])))

(defn todo-url [item]
  (route/url-for ::todo-show :params {:id (:id item)} :absolute? true))

(defn jsonify
  [m]
  (walk/postwalk-replace
   {:todo/title     :title
    :todo/completed :completed
    :todo/order     :order
    :db/id          :id}
   m))

(defn canonicalize
  [m]
  (assoc m :url (todo-url m)))

(defn respond-with-item
  [db id]
  (if-let [e (d/pull db '[*] id)]
    (ring-resp/response (canonicalize (jsonify e)))
    (ring-resp/not-found [])))

(helpers/defhandler todo-show
  [{db :db {:keys [id]} :path-params :as req}]
  (let [id (if (string? id) (edn/read-string id) id)]
    (respond-with-item db id)))

(helpers/defhandler todo-list
  [{:keys [db]}]
  (ring-resp/response
   (map canonicalize
        (sort-by :order
                 (jsonify
                  (mapcat identity
                          (d/q '[:find (pull ?id [*])
                                 :where [?id :todo/title]]
                               db)))))))

(helpers/defbefore todo-create
  [context]
  (let [req                   (:request context)
        {:keys [title order]} (:json-params req)
        datoms                (make-todo-datoms nil title false (if (string? order) (edn/read-string order) order))
        id                    (second (first datoms))
        tx-result             @(d/transact (:conn req) datoms)
        new-id                (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) id)]
    (-> context
        (assoc-in [:request :path-params :id] new-id)
        (assoc-in [:request :db] (:db-after tx-result)))))

(helpers/defhandler todo-delete
  [{conn :conn {:keys [id]} :path-params :as req}]
  (let [id        (if (string? id) (edn/read-string id) id)
        tx-result @(d/transact conn [[:db.fn/retractEntity id]])]
    (ring-resp/response "Deleted")))

(helpers/defbefore todo-delete-all
  [{{:keys [conn db]} :request :as context}]
  (let [retract-all (d/q '[:find ?retract-fn ?id
                           :in $ ?retract-fn
                           :where [?id :todo/title]]
                         db :db.fn/retractEntity)
        tx-result   @(d/transact conn (into [] retract-all))]
    (assoc-in context [:request :db] (:db-after tx-result))))

(helpers/defbefore todo-update
  [context]
  (let [req                             (:request context)
        {:keys [id]}                    (:path-params req)
        {:keys [title completed order]} (:json-params req)
        id                              (if (string? id)    (edn/read-string id)    id)
        order                           (if (string? order) (edn/read-string order) order)
        datoms                          (make-todo-datoms id title completed order)
        tx-result                       @(d/transact (:conn req) datoms)]
    (assoc-in context [:request :db] (:db-after tx-result))))

(defn routes
  [conn]
  (definition/expand-routes
    [[["/" {:get    todo-list
            :post   [:post-todo   ^:interceptors [todo-create]     todo-show]
            :delete [:delete-todo ^:interceptors [todo-delete-all] todo-list]}
       ^:interceptors [(body-params/body-params) (insert-datomic conn) bootstrap/json-body]
       ["/:id" {:get    todo-show
                :patch  [:patch-todo ^:interceptors [todo-update] todo-show]
                :delete todo-delete}]]]]))

(defn service
  [datomic-uri]
  (let [conn (d/connect datomic-uri)]
    {:env :prod
     ::bootstrap/routes (routes conn)
     ::bootstrap/allowed-origins ["http://www.todobackend.com"]
     ::bootstrap/resource-path "/public"
     ::bootstrap/type :jetty
     ::bootstrap/port 8080}))
