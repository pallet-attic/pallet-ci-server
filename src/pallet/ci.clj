(ns pallet.ci
  "Run script"
  (:require
   [pallet.core :as core]
   [pallet.compute :as compute]
   [pallet.compute.jclouds :as jclouds]
   [pallet.node.ci :as ci]))

(def node-count-property "pallet.ci.node-count")

(defn converge
  []
  (if-let [service (compute/compute-service-from-config-file :ci)]
    (let [node-count (read-string
                      (or (System/getProperty node-count-property) "1"))]
      (core/converge {ci/ci node-count} :compute service :phase :configure))
    (binding [*out* *err*]
      (println "Could not find :ci service in config.clj"))))
