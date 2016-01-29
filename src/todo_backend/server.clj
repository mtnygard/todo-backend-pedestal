(ns todo-backend.server
  (:gen-class)
  (:require [io.pedestal.http :as server]
            [todo-backend.service :as service]
            [environ.core :as env]
            [datomic.api :as d]))

(defonce uri (env/env :datomic-uri (str "datomic:mem://" (d/squuid))))

(defonce runnable-service (server/create-server (service/service uri)))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  (println "\nEnsuring Datomic schema...")
  (service/ensure-schema uri)
  (println "\nCreating your [DEV] server...")
  (let [conn (d/connect uri)]
    (-> (service/service uri) ;; start with production configuration
        (merge {:env :dev
                ;; do not block thread that starts web server
                ::server/join? false
                ;; Routes can be a function that resolve routes,
                ;;  we can use this to set the routes to be reloadable
                ::server/routes #(service/routes conn)
                ;; all origins are allowed in dev mode
                ::server/allowed-origins {:creds true :allowed-origins (constantly true)}})
        ;; Wire up interceptor chains
        server/default-interceptors
        server/dev-interceptors
        server/create-server
        server/start)))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nEnsuring Datomic schema...")
  (service/ensure-schema uri)
  (println "\nCreating your server...")
  (server/start runnable-service))
