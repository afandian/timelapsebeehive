(ns timelapsebeehive.core
  (:require [org.httpkit.server :as server])
  (:require [timelapsebeehive.handlers :as handlers])
  (:require [selmer.parser :refer [cache-off!]])
  (:gen-class))

; For use in REPL.
(defonce s (atom nil))

(def config {:port 6543})

(defn stop-server
  []
  (@s)
  (reset! s nil)
  (prn "Stop Server" @s))

(defn start-server []
  (cache-off!)
  (reset! s (server/run-server #'handlers/app {:port (:port config)}))
  (prn "Start Server" @s))

(defn restart-server []
  (stop-server)
  (start-server))


(defn -main
  [& args]
  (server/run-server #'handlers/app {:port (:port config)}))
