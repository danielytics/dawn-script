(defproject dawn "0.1.2-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [instaparse "1.4.10"]
                 [org.tomlj/tomlj "1.0.0"]
                 [slingshot "0.12.2"]
                 [tick "0.4.26-alpha"]
                 [com.taoensso/timbre "4.10.0"]
                 [erinite/utility "0.1.1-SNAPSHOT"]]
  :repl-options {:init-ns dawn.core}
  :profiles {:dev {:dependencies [[walmartlabs/datascope "0.1.1"]]
                   :plugins [[com.jakemccrary/lein-test-refresh "0.24.1"]]}})
  