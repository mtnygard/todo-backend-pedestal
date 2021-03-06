(defproject todo-backend-pedestal "0.0.1-SNAPSHOT"
  :description      "Todo Backend implementation using Pedestal and Datomic"
  :url              "http://example.com/FIXME"
  :license          {:name "Eclipse Public License"
                     :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies     [[org.clojure/clojure            "1.8.0"]
                     [io.pedestal/pedestal.service   "0.4.1"]
                     [io.pedestal/pedestal.jetty     "0.4.1"]
                     [environ                        "1.0.2"]
                     [com.datomic/datomic-free       "0.9.5344"]
                     [ch.qos.logback/logback-classic "1.1.3" :exclusions [org.slf4j/slf4j-api]]
                     [org.slf4j/jul-to-slf4j         "1.7.14"]
                     [org.slf4j/jcl-over-slf4j       "1.7.14"]
                     [org.slf4j/log4j-over-slf4j     "1.7.14"]]
  :plugins           [[lein-environ "1.0.0"]]
  :min-lein-version "2.0.0"
  :resource-paths   ["config", "resources"]
  :profiles         {:dev     {:env          {:is-dev      true
                                              :datomic-uri "datomic:free://localhost:4334/todo-backend"}
                               :aliases      {"run-dev" ["trampoline" "run" "-m" "todo-backend.server/run-dev"]}
                               :dependencies [[io.pedestal/pedestal.service-tools "0.4.1"]]}
                     :uberjar {:aot          [todo-backend.server]}}
  :main ^{:skip-aot true} todo-backend.server)
