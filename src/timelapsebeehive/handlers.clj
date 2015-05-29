(ns timelapsebeehive.handlers
  (:require [timelapsebeehive.database :as db])
  (:require [clojure.java.io :as io])
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
            [ring.middleware.stacktrace :refer [wrap-stacktrace]])
  (:require [liberator.core :refer [defresource resource]]
            [liberator.representation :refer [ring-response]])
  (:require [camel-snake-kebab.core :refer [->kebab-case-keyword ->camelCaseString]])
  (:require [clj-time.format :as format])
  (:require [clojure.walk :refer [prewalk]])
  (:require [selmer.parser :refer [render-file]]))

(defn wrap-dir-index [handler]
  (fn [req]
    (handler
     (update-in req [:uri]
                #(if (= "/" %) "/index.htm" %)))))

(def iso-format (format/formatters :date-time))
(defn export-all-dates
  "Convert all dates in structure"
  [input]
    (prewalk #(if (= (type %) org.joda.time.DateTime)
                (format/unparse iso-format %)
                %)
           input))

(defn export-hive [hive]
  (-> hive
       (select-keys [:name :notes])
       (assoc :link (str "/hives/" (:id hive)))))

(defn export-user [user include-hives]
  (-> user
       (select-keys [:inspectionsFree :recordingsFree :username :notes])
       (assoc :link (str "/users/" (:username user))
              :hives-link (str "/users/" (:username user) "/hives"))
       (#(if include-hives (assoc %
                             :hives
                             (map (fn [hive] (export-hive user hive)) (db/hives-for-user (:id user)))
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
               (prn "OK?")
              (let [users (db/all-users)
                    users (map #(export-user % true) users)
                    response {:users users :navigation {:page "story"}}]
                (render-response ctx response "templates/story.html"))))

(defresource hives
  []
  :allowed-methods [:get]
  :available-media-types ["application/json" "text/html"]
  :handle-ok (fn [ctx]
              (let [hives (db/all-hives)
                    hives (map #(export-hive % true) hives)
                    response {:hives hives :navigation {:page "hives"}}]
                (render-response ctx response "templates/hives.html"))))
                
                
(defroutes app-routes
  (GET "/" [] (index))
  (GET "/story" [] (story))
  (GET "/hives" [] (hives))
  
  (route/resources "/" {:root "public"})
  
  (route/not-found "<h1>Page not found!</h1>"))

(def app
  (-> app-routes
     (wrap-session {:store (cookie-store {:key "TODO"})})
     (wrap-stacktrace)
     (wrap-params)))
