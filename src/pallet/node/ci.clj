(ns pallet.node.ci
  "Pallet CI Server Node"
  (:require
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.crontab :as crontab]
   [pallet.crate.git :as git]
   [pallet.crate.gpg :as gpg]
   [pallet.crate.java :as java]
   [pallet.crate.hudson :as hudson]
   [pallet.crate.iptables :as iptables]
   [pallet.crate.ssh :as ssh]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.tomcat :as tomcat]
   [pallet.maven :as maven]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [pallet.resource.directory :as directory]
   [pallet.resource.file :as file]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.service :as service]
   [pallet.resource.user :as user]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]))


(defn generate-ssh-keys
  "Generate key for testing clj-ssh and pallet"
  [request]
  (let [user (parameter/get-for-target request [:hudson :user])]
    (->
     request
     (user/user user :comment "\"hudson,,,\"")
     (directory/directory
      (stevedore/script (str (user-home ~user) "/.m2"))
      :owner user :mode "755")
     (remote-file/remote-file
      (stevedore/script (str (user-home ~user) "/.m2/settings.xml"))
      :local-file "/Users/pallet/settings.xml"
      :owner user :mode "600")
     (gpg/import-key :local-file "/Users/pallet/pallet-signer" :user user)
     (ssh-key/generate-key user :comment "pallet test key")
     (ssh-key/generate-key user :file "clj_ssh" :comment "clj-ssh test key")
     (ssh-key/generate-key user :file "clj_ssh_pp" :passphrase "clj-ssh"
                           :comment "clj-ssh test key with passphrase")
     (ssh-key/authorize-key-for-localhost
      user "id_rsa.pub" :authorize-for-user "testuser")
     (ssh-key/authorize-key-for-localhost
      user "clj_ssh.pub" :authorize-for-user "testuser")
     (ssh-key/authorize-key-for-localhost
      user "clj_ssh_pp.pub" :authorize-for-user "testuser")
     (ssh-key/record-public-key user))))

(defn ci-config
  [request]
  (let [properties (maven/properties [])]
    (->
     request
     (package/package "maven2")
     (git/git)
     (gpg/gpg)
     (java/java)
     (tomcat/tomcat)
     (iptables/iptables-accept-port 8080)
     (iptables/iptables-redirect-port 80 8080)
     (user/user "testuser" :create-home true :shell :bash)
     (service/with-restart "tomcat6"
       (tomcat/server-configuration (tomcat/server))
       (hudson/tomcat-deploy)
       (thread-expr/binding->
        [remote-file/force-overwrite true]
        (hudson/config
         :use-security true
         :security-realm :hudson
         :authorization-strategy :global-matrix
         :permissions [{:user "hugo" :permissions hudson/all-permissions}
                       {:user (properties :hudson.ircbot.user)
                        :permissions #{:hudson-read
                                       :item-build :item-read
                                       :item-workspace :run-delete :run-update
                                       :scm-tag}}
                       {:user "tbatchelli" :permissions hudson/all-permissions}
                       {:user "ambrosebs" :permissions hudson/all-permissions}
                       {:user "Anonymous"
                        :permissions [:item-read :hudson-read]}]
         :admin-user (properties :hudson.hugo.user)
         :admin-password (properties :hudson.hugo.password))
        (generate-ssh-keys)
        (hudson/plugin :git :version "1.1.5")
        (hudson/plugin :github :version "0.4")
        (hudson/plugin :instant-messaging :version "1.13")
        (hudson/plugin :greenballs :version "1.10")
        (hudson/plugin
         :ircbot
         :version "2.9"
         :enabled true :hostname "irc.freenode.net" :port 6667
         :nick "palletci" :nick-serv-password "hudsonci"
         :hudson-login (properties :hudson.ircbot.user)
         :hudson-password (properties :hudson.ircbot.password)
         :default-targets [{:name "#pallet"}]
         :command-prefix "palletci:")
        (hudson/user
         (properties :hudson.hugo.user)
         {:full-name "Hugo Duncan"
          :password-hash (properties :hudson.hugo.hash)
          :email "hugo_duncan@yahoo.com"})
        (hudson/user
         (properties :hudson.ircbot.user)
         {:full-name "IRC bot"
          :password-hash (properties :hudson.ircbot.hash)
          :email "ircbot_duncan@yahoo.com"})
        (hudson/maven "default maven" "3.0.3")
        (hudson/job :maven2 "pallet"
                    :maven-name "default maven"
                    :goals "-P testuser,jclouds clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "pallet"
                    :branches ["origin/develop"]
                    :github {:projectUrl "https://github.com/pallet/pallet/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/pallet.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}}
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "pallet-features"
                    :maven-name "default maven"
                    :goals "-P testuser,jclouds clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "pallet"
                    :branches ["origin/feature/*"]
                    :github {:projectUrl "https://github.com/pallet/pallet/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/pallet.git"]
                    :num-to-keep 10
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "pallet-crates"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "pallet-crates"
                    :branches ["origin/develop"]
                    :github {:projectUrl
                             "https://github.com/pallet/pallet-crates/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/pallet-crates.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}}
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "pallet-crates-features"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "pallet-crates"
                    :branches ["origin/feature/*"]
                    :github {:projectUrl
                             "https://github.com/pallet/pallet-crates/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/pallet-crates.git"]
                    :num-to-keep 10

                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "pallet-apache-crates"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "pallet-apache-crates"
                    :branches ["origin/develop" "origin/feature/*"]
                    :merge-target "master"
                    :github {:projectUrl
                             "https://github.com/pallet/pallet-apache-crates/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/pallet-apache-crates.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}}
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build}}]})
        (hudson/job :maven2 "pallet-all"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "pallet-all"
                    :branches ["origin/develop" "origin/feature/*"]
                    :merge-target "master"
                    :github {:projectUrl "https://github.com/pallet/pallet-all/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/pallet-all.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}})
        (hudson/job :maven2 "pallet-pom"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "pallet-pom"
                    :branches ["origin/develop" "origin/feature/*"]
                    :merge-target "master"
                    :github {:projectUrl "https://github.com/pallet/pallet-pom/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/pallet-pom.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}}
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "thread-expr"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "thread-expr"
                    :branches ["origin/develop"]
                    :github {:projectUrl "https://github.com/pallet/thread-expr/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/thread-expr.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}}
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "common"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "thread-expr"
                    :branches ["origin/develop"]
                    :github {:projectUrl "https://github.com/pallet/common/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/common.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}}
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "stevedore"
                    :maven-name "default maven"
                    :goals "clean deploy"
                    :group-id "org.cloudhoist"
                    :artifact-id "thread-expr"
                    :branches ["origin/develop"]
                    :github {:projectUrl "https://github.com/pallet/common/"}
                    :aggregator-style-build true
                    :maven-opts ""
                    :scm ["git://github.com/pallet/stevedore.git"]
                    :num-to-keep 10
                    :triggers {:scm-trigger "*/15 * * * *"}
                    :publishers {:ircbot
                                 {:targets [{:name "#pallet"}]
                                  :strategy :all}}
                    :properties
                    {:authorization-matrix
                     [{:user "Anonymous"
                       :permissions #{:item-read :item-build :item-workspace}}]})
        (hudson/job :maven2 "clj-ssh"
                    :maven-name "default maven"
                    :goals "-Ptestuser clean test"
                    :group-id "clj-ssh"
                    :artifact-id "clj-ssh"
                    :branches ["origin/master"]
                    :maven-opts ""
                    :github {:projectUrl "https://github.com/hugoduncan/clj-ssh/"}
                    :scm ["git://github.com/hugoduncan/clj-ssh.git"]
                    :num-to-keep 10))))))


(defn remove-ci
  [request]
  (->
   request
   (hudson/tomcat-undeploy)
   (package/package "maven2" :action :remove :purge true)
   (user/user "testuser" :action :remove)
   (tomcat/tomcat :action :remove :purge true)))

(core/defnode ci
  {:any true :min-ram 512 :os-family :ubuntu
   :os-description-matches ".*10.10.*"}
  :bootstrap (resource/phase
              (automated-admin-user/automated-admin-user))
  :configure (resource/phase
              (iptables/iptables-accept-icmp)
              (iptables/iptables-accept-established)
              (ssh/iptables-throttle)
              (ssh/iptables-accept)
              (thread-expr/binding-> [remote-file/force-overwrite true]
                (ci-config))
              (crontab/system-crontab
               "clj-mvn-tmpfiles"
               :content (stevedore/script
                         (rm (quoted "/tmp/class*dir")
                             {:force true :recursive true})
                         (rm (quoted "/tmp/pallet*tmp*")
                             {:force true :recursive true})
                         (rm (quoted "/tmp/run-test*.clj") {:force true}))
               :literal true))
  :restart-tomcat (resource/phase
                   (service/service "tomcat6" :action :restart))
  :reload-configuration (resource/phase
                         (hudson/reload-configuration))
  :build-clj-ssh (resource/phase (hudson/build "clj-ssh"))
  :build-pallet (resource/phase (hudson/build "pallet")))
