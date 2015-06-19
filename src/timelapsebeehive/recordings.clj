(ns timelapsebeehive.recordings
  (:require [timelapsebeehive.util :as util]
             [timelapsebeehive.database :as db])
  (:import (java.io File InputStream FileInputStream))
  (:require [clojure.core.async :refer [chan >! >!! <! <!! go]])
  (:gen-class))

; TODO temporary
(def config {:storage-dir "/Users/joe/personal/langstroth-storage"})


(def process-queue (chan 1000))

(def max-slice-count 121)

(defn path-for-sample [hive-id timestamp]
  (.getPath (new File (new File (:storage-dir config)) (str "/recordings/" hive-id "/" timestamp ".wav"))))

(defn shell [args]
  (prn "Shell" args)
  (let [s (.exec (Runtime/getRuntime) (into-array String args))]
      (prn "WAIT" (.waitFor s))))

(defn process-file
  [f]
  (let [wav-filename (.getAbsolutePath f)
        output-filename (str (.substring wav-filename 0 (- (.length wav-filename) 4)) ".short.wav")]
    (prn "Convert " wav-filename)
    ; Trim one second's worth. 
    ; Microphone input seems to be doing some auto-calibrating in the first second.
    (shell ["sox" wav-filename output-filename "trim" "3" "1"])
    (let [output-file (new File output-filename)]
      (when (.exists output-file)
        (.delete f)
        ; Rename back to original filename.
        (.renameTo output-file (new File wav-filename))))))
  
(defn start-process-queue []
  (go
    (prn "Process queue...")
    (loop [f (<! process-queue)]
      (prn "Process queue tick")
        (when f
          (do 
            (process-file f)
            (recur (<! process-queue)))))))



(defn abs [n] (max n (- n)))


(defn nearest
  "For list of timestamps, find the nearest."
  [sought items]
  (let [with-distances (map (fn [timestamp]
                              [(abs (- sought timestamp)) timestamp]) items)
        best (first (sort-by first with-distances))]
    ; Return the timestamp (throw away distance).
    (second best)))

(defn generate-splice
  "Generate a spliced file and return a File."
  [hive-id start end skip]
  (let [output (new File (new File (:storage-dir config)) (str "/timelapse/" hive-id "/" start "-" end "-" skip ".mp3"))]
    (if (.exists output)
      output
      (let [sample-timestamps (db/samples-between hive-id start end)]
        (when sample-timestamps)
          (let [time-range (range start end skip)
               
                nearest (map #(nearest % sample-timestamps) time-range)
                paths (map (partial path-for-sample hive-id) nearest)
                
                ; TODO could use ffmpeg and use an unlimited number of input files
                concat-command (concat ["sox" "--combine" "concatenate"] paths [(str (.getAbsolutePath output))])]

            (.mkdirs (.getParentFile output))
            (shell concat-command)
            output)))))

(defn generate-spectrogram
  "Generate spectrogram from mp3"
  [hive-id start end skip input-f width height legend]
  
  (let [output (new File (new File (:storage-dir config)) (str "/spectrograms/" hive-id "/" start "-" end "-" skip "-" width "x" height "-" legend ".png"))]
    (prn "GENERATE" output (.exists output))
    (if (.exists output)
      output
      (let [duration-millis (- end start)
            duration-minutes (/ duration-millis (* 1000 60))
            units (cond
                    ; Less than 1 hr
                    (< duration-minutes 60) :minutes
                    ; Less than 24 hr
                    (< duration-minutes (* 60 24)) :hours
                    :default :days)
            title (str "Langstroth - " (condp = units
                                         :minutes (str duration-minutes " minutes")
                                         :hours (str (int (/ duration-minutes 60)) " hours")
                                         :days (str (int (/ duration-minutes (* 60 24))) " days")))
            
            spectrogram-command ["sox" (str (.getAbsolutePath input-f)) "-n" "spectrogram" "-l" "-t" title "-o" (str (.getAbsolutePath output)) "-x" (str width) "-y" (str height)]
            spectrogram-command (if-not legend (conj spectrogram-command "-r") spectrogram-command)
            ]
    (.mkdirs (.getParentFile output))
    (shell spectrogram-command)
    output))))

