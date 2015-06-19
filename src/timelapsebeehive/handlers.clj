(ns timelapsebeehive.handlers
  (:require [timelapsebeehive.database :as db]
            [timelapsebeehive.recordings :as recordings]
            [timelapsebeehive.util :as util]
            [timelapsebeehive.util :refer [config]])
  (:require [clojure.java.io :as io]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce])
  (:require [liberator.core :refer [defresource]])
  (:require [compojure.handler :as handler]
            [compojure.core :refer :all]
            [compojure.route :as route])
  (:require [clojure.data.json :as json]
            [clojure.string :as string])
  (:require [ring.middleware.session :as session]
            [ring.util.response :refer [response redirect status content-type header]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.middleware.basic-authentication :refer [wrap-basic-authentication]])
  (:require [liberator.core :refer [defresource resource]]
            [liberator.representation :refer [ring-response]])
  (:require [camel-snake-kebab.core :refer [->kebab-case-keyword ->camelCaseString]])
  (:require [clj-time.format :as format])
  (:require [clojure.walk :refer [prewalk]])
  (:require [selmer.parser :refer [render-file]])
  (:require [clojure.core.async :refer [>!!]])
  (:import (java.io File InputStream FileInputStream)))

; Maximum number of recordings that can be spliced into one timelapse (limitation of command-line).
(def max-slice-count 121)

(defn authorized-handler
  "Return user id if logged in."
  [ctx]
  (when-let [user-id (-> ctx :request :session :user-id)]
    {::user-id user-id}))


(def iso-format (format/formatters :date-time))
(defn export-all-dates
  "Convert all dates in structure"
  [input]
    (prewalk #(if (= (type %) org.joda.time.DateTime)
                (format/unparse iso-format %)
                %)
           input))

(defn export-hive [hive]
  (let [user (db/user-by-id (:owner hive))]
    (-> hive
         (select-keys [:name :notes])
         (assoc :link (str "/hives/" (:id hive))
                :timelapse-link (str "/hives/" (:id hive) "/timelapse")
                :spectrogram-link (str "/hives/" (:id hive) "/spectrogram")
                :user (:username user)))))

(defn export-user [user include-hives]
  (-> user
       (select-keys [:username :notes])
       (assoc :link (str "/users/" (:username user))
              :hives-link (str "/users/" (:username user) "/hives"))
       (#(if include-hives (assoc %
                             :hives
                             (map (fn [hive] (export-hive hive)) (db/hives-for-user (:id user)))
                             ) %))))


(defn render-response
  "Return the correct response type (json or templated HTML)"
  [ctx response template]
  (condp = (get-in ctx [:representation :media-type])
                    "text/html" (render-file template response)
                    ; Middleware will take care of serializing.
                    "application/json" (export-all-dates response)))

(defresource index
  []
  :allowed-methods [:get]
  :available-media-types ["application/json" "text/html"]
  :handle-ok (fn [ctx]
              (let [users (db/all-users)
                    users (map #(export-user % true) users)
                    response {:users users :navigation {:page "index"}}]
                (render-response ctx response "templates/index.html"))))
                
(defresource story
  []
  :allowed-methods [:get]
  :available-media-types ["application/json" "text/html"]
  :handle-ok (fn [ctx]
              (let [users (db/all-users)
                    users (map #(export-user % true) users)
                    response {:users users :navigation {:page "story"}}]
                (render-response ctx response "templates/story.html"))))

(defresource faq
  []
  :allowed-methods [:get]
  :available-media-types ["application/json" "text/html"]
  :handle-ok (fn [ctx]
              (let [users (db/all-users)
                    users (map #(export-user % true) users)
                    response {:users users :navigation {:page "faq"}}]
                (render-response ctx response "templates/faq.html"))))

(defresource open-source
  []
  :allowed-methods [:get]
  :available-media-types ["application/json" "text/html"]
  :handle-ok (fn [ctx]
              (let [users (db/all-users)
                    users (map #(export-user % true) users)
                    response {:users users :navigation {:page "open-source"}}]
                (render-response ctx response "templates/open-source.html"))))

(defresource hives
  []
  :allowed-methods [:get]
  :available-media-types ["application/json" "text/html"]
  :handle-ok (fn [ctx]
              (let [hives (db/all-hives)
                    hives (map #(export-hive %) hives)
                    response {:hives hives :navigation {:page "hives"}}]
                (render-response ctx response "templates/hives.html"))))

(defresource hive
  [id]
  :allowed-methods [:get]
  :available-media-types ["application/json" "text/html"]
  :exists? (fn [ctx]
             (let [hive (db/hive-by-id id)]
               [hive {::hive hive}]))
  :handle-ok (fn [ctx]
              (let [hive (::hive ctx)
                    earliest-sample (db/earliest-sample-for-hive (:id hive))
                    latest-sample (db/latest-sample-for-hive (:id hive))
                    day-range (when (and earliest-sample latest-sample) (util/day-range (:datetime earliest-sample) (:datetime latest-sample)))
                    
                    day-range (when day-range (map (fn [date] {:date date
                                               :start-timestamp (coerce/to-long date)
                                               :end-timestamp (coerce/to-long (time/plus date (time/hours 24)))
                                               }) day-range))
                    
                    days-with-recordings-range (filter #(db/any-samples-for-hive-between (:id hive) (:start-timestamp %) (:end-timestamp %)) day-range)
                    
                    response {:hive (export-hive hive)
                              :earliest-sample earliest-sample
                              :latest-sample latest-sample
                              :day-range days-with-recordings-range
                              :navigation {:page "hives"}}]
                
                (prn days-with-recordings-range)
                (render-response ctx response "templates/hive.html"))))
                

(defresource recording
  [hive duration filename]
  :allowed-methods [:put :get]
  :available-media-types ["audio/wav"]
  :authorized? authorized-handler
  :exists? (fn [ctx]
             ; TODO path-for-sample
             (let [filename (if (.endsWith filename ".wav") (.substring filename 0 (- (.length filename) 4)) filename)
                   f (new File (new File (:storage-dir config)) (str "recordings/" hive "/" filename ".wav"))
                   exists (.exists f)]
               [exists {::file f ::filename filename}]))
  
  :handle-ok (fn [ctx] (let [f (::file ctx)]
               (new FileInputStream f)))

  :put! (fn [ctx]
            (let [f (::file ctx)]
              (.mkdirs (.getParentFile f))
              (prn (str "Uploading " f))
              (with-open [is (clojure.java.io/input-stream (get-in ctx [:request :body]))]
                (with-open [os (clojure.java.io/output-stream f)]
                  (clojure.java.io/copy is os)))
              (db/insert-sample hive (::filename ctx))
              (prn "Put on process queue" f)
              (>!! recordings/process-queue f)
              true)))
                
      
; Perform login with Basic Authentication and set cookie.
(defresource login
  [auth]
  :allowed-methods [:post :get]
  :available-media-types ["text/plain", "text/html"]
  :accepts ["application/json"]
  :handle-ok (fn [ctx]
               (ring-response {:headers {"Content-Type" "text/plain"
                                         "User-Id" (:basic-authentication auth)}
                               ; :basic-authentication is the response of `authenticated?`
                               :session {:user-id (:basic-authentication auth)}
                                :body "ok"})))

; Simple handler to check if the session is authenticated.
(defresource authenticated
  []
  :available-media-types ["text/plain", "text/html"]
  :allowed-methods [:get :head]
  :authorized? authorized-handler
  :handle-ok (fn [ctx]
               (ring-response {:headers {"Content-Type" "text/plain"
                                         "User-Id" (::user-id ctx)}                               
                                :body "ok"})))

(defresource recordings-info
  [user entity]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
              (let [files (.listFiles (new File (new File (:storage-dir config)) (str "recordings/" user "/" entity)))
                    files (filter #(.endsWith (.getName %) ".wav") files)
                    timestamps (map (fn [f]
                                     (let [nom (.getName f)
                                           timestamp-str (.substring nom 0 (- (.length nom) 4))
                                           timestamp (util/parse-int timestamp-str)]
                                        timestamp)) files)
                    earliest (apply min timestamps)
                    latest (apply max timestamps)]
                {:earliest earliest :latest latest :max-slice-count max-slice-count})))          
                
                
(defresource hive-timelapse
  [hive-id]
  :available-media-types ["audio/mp3"]
  :malformed? (fn [ctx] 
                (let [start (util/parse-int (get-in ctx [:request :params "start"]))
                      end (util/parse-int (get-in ctx [:request :params "end"]))
                      skip (util/parse-int (get-in ctx [:request :params "skip"] ))
                      ; If this requires more than this many files, don't do it.
                      ; Can be solved by upping the skip.
                      acceptable-slice-count (< (/ (- end start) skip) max-slice-count)]
                          [(not (and start end skip acceptable-slice-count (> end start) ))
                           {::start start ::end end ::skip skip}]))
  :exists? (fn [ctx] (let [hive (db/hive-by-id hive-id)
                           start (::start ctx)
                             end (::end ctx)
                             skip (::skip ctx)
                             output-f (recordings/generate-splice (:id hive) start end skip)]
                       ; May return nil if nothing matched.
                       [output-f {::output-f output-f}]))
  :handle-ok (fn [ctx] (new FileInputStream (::output-f ctx))))

(defresource hive-spectrogram
  [hive-id]
  :available-media-types ["image/png"]
  :malformed? (fn [ctx] 
                (let [start (util/parse-int (get-in ctx [:request :params "start"]))
                      end (util/parse-int (get-in ctx [:request :params "end"]))
                      skip (util/parse-int (get-in ctx [:request :params "skip"] ))
                      width (util/parse-int (get-in ctx [:request :params "width"] "100"))
                      height (util/parse-int (get-in ctx [:request :params "height"] "800"))
                      legend (= "true" (get-in ctx [:request :params "legend"]))
                      
                      ; If this requires more than this many files, don't do it.
                      ; Can be solved by upping the skip.
                      acceptable-slice-count (< (/ (- end start) skip) max-slice-count)]
                      
                  [(not (and start end skip acceptable-slice-count (> end start)))
                   {::start start ::end end ::skip skip ::width width ::height height ::legend legend}]))
  :exists? (fn [ctx]   (let [hive (db/hive-by-id hive-id)
                             start (::start ctx)
                             end (::end ctx)
                             skip (::skip ctx)
                             width (::width ctx)
                             height (::height ctx)
                             legend (::legend ctx)
                             mp3-f (recordings/generate-splice hive-id start end skip)
                             spectrogram-f (when mp3-f (recordings/generate-spectrogram (:id hive) start end skip mp3-f width height legend))]
            ; May return nil if nothing matched.
                       [spectrogram-f {::spectrogram-f spectrogram-f}]))
  :handle-ok (fn [ctx] (new FileInputStream (::spectrogram-f ctx))))

(defn authenticated? [username password]
  (when (and 
          (= (-> config :creds :username) username)
          (= (-> config :creds :password) password))
    username))
   
(defroutes app-routes
  (GET "/" [] (index))
  (GET "/story" [] (story))
  (GET "/hives" [] (hives))
  (GET "/faq" [] (faq))
  (GET "/open-source" [] (open-source))
  (GET "/hives/:id" [id] (hive id))
  (GET "/hives/:id/spectrogram" [id] (hive-spectrogram id))
  (GET "/hives/:id/timelapse" [id] (hive-timelapse id))
  
  (ANY "/login" [] (wrap-basic-authentication login authenticated?))
  (ANY "/authenticated" [] (authenticated))
  (ANY "/recordings/:hive/:duration/:filename" [hive duration filename] (recording hive duration filename))
  
  (route/resources "/" {:root "public"})
  
  (route/not-found "<h1>Page not found!</h1>"))

(def app
  (-> app-routes
     (wrap-session {:store (cookie-store {:key (.getBytes (:cookie-store-key config) "utf-8")})})
     (wrap-stacktrace)
     (wrap-params)))