{:dev
 {:dependencies [[ch.qos.logback/logback-classic "1.0.0"]]
  :plugins [[lein-pallet-release "RELEASE"]]
  :aliases {"test" ["with-profile"
                    "clojure-1.4.0:clojure-1.5.1:clojure-1.6.0:clojure-1.7.0:clojure-1.8.0"
                    "test"]}}
 :clojure-1.2.1 {:dependencies [[org.clojure/clojure "1.2.1"]]}
 :clojure-1.3.0 {:dependencies [[org.clojure/clojure "1.3.0"]]}
 :clojure-1.4.0 {:dependencies [[org.clojure/clojure "1.4.0"]]}
 :clojure-1.5.1 {:dependencies [[org.clojure/clojure "1.5.1"]]}
 :clojure-1.6.0 {:dependencies [[org.clojure/clojure "1.6.0"]]}
 :clojure-1.7.0 {:dependencies [[org.clojure/clojure "1.7.0"]]}
 :clojure-1.8.0 {:dependencies [[org.clojure/clojure "1.8.0"]]}}
