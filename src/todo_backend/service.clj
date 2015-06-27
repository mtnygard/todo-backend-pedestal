(ns todo-backend.service
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [datomic.api :as d]
            [environ.core :as env]
            [io.pedestal.http :as bootstrap]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition :as definition]
            [io.pedestal.interceptor :as interceptor]
            [ring.util.response :as ring-resp]))

(defonce uri (env/env :datomic-uri (str "datomic:mem://" (d/squuid))))

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
  []
  (d/create-database uri)
  @(d/transact (d/connect uri) schema))

(def insert-datomic
  "Provide a Datomic conn and db in all incoming requests"
  (interceptor/interceptor
   {:name ::insert-datomic
    :enter (fn [context]
             (let [conn (d/connect uri)]
               (-> context
                   (assoc-in [:request :conn] conn)
                   (assoc-in [:request :db] (d/db conn)))))}))

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

(defn todo-show
  [{db :db {:keys [id]} :path-params :as req}]
  (let [id (if (string? id) (edn/read-string id) id)]
    (respond-with-item db id)))

(defn todo-list
  [{:keys [db]}]
  (ring-resp/response
   (map canonicalize
        (sort-by :order
                 (jsonify
                  (mapcat identity
                          (d/q '[:find (pull ?id [*])
                                 :where [?id :todo/title]]
                               db)))))))

(defn todo-create
  [{conn :conn {:keys [title order]} :json-params :as req}]
  (let [datoms    (make-todo-datoms nil title false (if (string? order) (edn/read-string order) order))
        id        (second (first datoms))
        tx-result @(d/transact conn datoms)
        new-id    (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) id)]
    (respond-with-item (:db-after tx-result) new-id)))

(defn todo-delete
  [{conn :conn {:keys [id]} :path-params :as req}]
  (let [id        (if (string? id) (edn/read-string id) id)
        tx-result @(d/transact conn [[:db.fn/retractEntity id]])]
    (ring-resp/response "Deleted")))

(defn todo-delete-all
  [{:keys [conn db] :as req}]
  (let [retract-all (d/q '[:find ?retract-fn ?id
                           :in $ ?retract-fn
                           :where [?id :todo/title]]
                         db :db.fn/retractEntity)
        tx-result   @(d/transact conn (into [] retract-all))]
    (todo-list (assoc req :db (:db-after tx-result)))))

(defn todo-update
  [{conn :conn {:keys [id]} :path-params {:keys [title completed order]} :json-params :as req}]
  (let [id        (if (string? id)    (edn/read-string id)    id)
        order     (if (string? order) (edn/read-string order) order)
        datoms    (make-todo-datoms id title completed order)
        tx-result @(d/transact conn datoms)]
    (respond-with-item (:db-after tx-result) id)))

(definition/defroutes routes
  ;; Defines "/" and "/about" routes with their associated :get handlers.
  ;; The interceptors defined after the verb map (e.g., {:get home-page}
  ;; apply to / and its children (/about).
  [[["/" {:get    todo-list
          :post   todo-create
          :delete todo-delete-all}
     ^:interceptors [(body-params/body-params) insert-datomic bootstrap/json-body]
     ["/:id" {:get    todo-show
              :patch  todo-update
              :delete todo-delete}]]]])

;; Consumed by todo-backend-pedestal.server/create-server
;; See bootstrap/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::bootstrap/interceptors []
              ::bootstrap/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ::bootstrap/allowed-origins ["http://www.todobackend.com"]

              ;; Root for resource interceptor/interceptor that is available by default.
              ::bootstrap/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
              ::bootstrap/type :jetty
              ;;::bootstrap/host "localhost"
              ::bootstrap/port 8080})
