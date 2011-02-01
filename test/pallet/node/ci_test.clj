(ns pallet.node.ci-test
  (:use
   pallet.node.ci
   clojure.test)
  (:require
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.git :as git]
   [pallet.crate.gpg :as gpg]
   [pallet.crate.java :as java]
   [pallet.crate.hudson :as hudson]
   [pallet.crate.iptables :as iptables]
   [pallet.crate.ssh :as ssh]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.tomcat :as tomcat]
   [pallet.live-test :as live-test]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.network-service :as network-service]
   [pallet.resource.user :as user]
   [pallet.utils :as utils]
   [pallet.parameter-test :as parameter-test]
   [net.cgrand.enlive-html :as xml]))


(deftest live-test
  (doseq [image [{:os-family :ubuntu :os-version-matches "10.04"}]]
    (live-test/test-nodes
     [compute node-map node-types]
     {:citest
      {:image image
       :count 1
       :phases {:bootstrap (resource/phase
                            (automated-admin-user/automated-admin-user))
                :configure (resource/phase
                            (iptables/iptables-accept-icmp)
                            (iptables/iptables-accept-established)
                            (ssh/iptables-throttle)
                            (ssh/iptables-accept)
                            (ci-config))
                :verify (resource/phase
                         ;; hudson takes a while to start up
                         (network-service/wait-for-http-status
                          "http://localhost:8080/hudson" 200 :url-name "hudson")
                         (exec-script/exec-checked-script
                          "check hudson installed"
                          (wget "-O-" "http://localhost:8080/hudson")
                          (wget "-O-" "http://localhost:8080/hudson/job/gitjob")
                          (wget
                           "-O-" "http://localhost:8080/hudson/job/svnjob")))}}}
     (core/lift (:hudson node-types) :phase :verify :compute compute))))
