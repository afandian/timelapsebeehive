(ns timelapsebeehive.core
  (:require [org.httpkit.server :as server])
  (:require [timelapsebeehive.handlers :as handlers]
            [timelapsebeehive.util :refer [config]]
            [timelapsebeehive.recordings :as recordings]
            [timelapsebeehive.database :as database])
  (:require [selmer.parser :refer [cache-off!]])
  (:import (java.io File))
  (:gen-class))

; For use in REPL.
(defonce s (atom nil))

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
  (prn args)
  (when (= (first args) "import")
    (let [hive (nth args 1)
          dir-path (nth args 2)
          dir (new File dir-path)
          files (.listFiles dir)
          ]
      (prn "Import hive " hive " from " dir)
      (doseq [input-file files]
        (let [filename (.getName input-file)
              filename (if (.endsWith filename ".wav") (.substring filename 0 (- (.length filename) 4)) filename)
              destination-file (new File (new File (:storage-dir config)) (str "recordings/" hive "/" filename ".wav"))]
        
        (prn "Copy " input-file " to" destination-file)
        (with-open [is (clojure.java.io/input-stream input-file)]
                (with-open [os (clojure.java.io/output-stream destination-file)]
                  (clojure.java.io/copy is os)))
        (prn "Process destination-file")
        (recordings/process-file destination-file)
        (database/insert-sample hive filename)
        )))
    (prn "Done"))
  
  (when (empty? args)
    (server/run-server #'handlers/app {:port (:port config)})))
