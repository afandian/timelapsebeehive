(ns timelapsebeehive.util
  (:require [clojure.tools.reader.edn :as edn])
  (:require [clj-time.core :as time]
            [clj-time.periodic :as time-period])
  (:require [clojure.tools.logging :refer [error info]]))


(defn parse-int [input]
  (try 
    (new BigInteger input)
  (catch NumberFormatException _ nil)))


;http://www.rkn.io/2014/02/13/clojure-cookbook-date-ranges/
(defn time-range
  "Return a lazy sequence of DateTimes from start to end, incremented
  by 'step' units of time."
  [start end step]
    
  (let [inf-range (time-period/periodic-seq start step)
        below-end? (fn [t] (time/within? (time/interval start end)
                                         t))]
    (take-while below-end? inf-range)))

(defn day-range
  "Range of days, including start and end day, at midnight."
  [start end]
  (time-range 
    (time/date-time (time/year start) (time/month start) (time/day start))
    (time/plus
      (time/date-time (time/year end) (time/month end) (time/day end))
      (time/days 1))
    (time/days 1)))


(def config-files ["config.edn" "config.dev.edn"])

(def first-extant-file (first (filter #(.exists (clojure.java.io/as-file %)) config-files)))

(def config
  (let [file-to-use first-extant-file]
    (if-not file-to-use
      (error "Can't find config file")
      (let [config-file (slurp file-to-use)
            the-config (edn/read-string config-file)]
            (info "Using config file" first-extant-file ", values: " the-config)
            the-config))))
