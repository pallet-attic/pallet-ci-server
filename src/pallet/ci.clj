;; Run script
(require
 '[pallet.core :as core]
 '[pallet.compute :as compute]
 '[pallet.compute.jclouds :as jclouds]
 '[pallet.node.ci :as ci]
 '[clojure.contrib.logging :as logging])

(def node-count-property "pallet.ci.node-count")


(if-let [service (compute/compute-service-from-config-file :ci)]
  (try
    (let [node-count (read-string
                      (or (System/getProperty node-count-property) "1"))]
      (core/converge {ci/ci node-count} :compute service :phase :configure))
    (System/exit 0)
    (catch Exception e
      (logging/error "Unexpected error" e)
      (System/exit 1)))
  (binding [*out* *err*]
    (println "Could not find :ci service in config.clj")))
