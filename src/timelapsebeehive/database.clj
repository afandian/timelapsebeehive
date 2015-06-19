(ns timelapsebeehive.database
    (:require [timelapsebeehive.util :refer [config]])
    (:require [korma.core :refer [defentity entity-fields pk table select subselect where order insert update values delete exec-raw set-fields fields sql-only join aggregate group with has-one has-many limit offset modifier belongs-to transform]])
    (:require [korma.db :refer [mysql with-db defdb]])
    (:require [clj-time.coerce :as coerce]))

(defdb db
  (mysql {:user (:db-username config)
          :password (:db-password config)
          :db (:db-name config)}))

(defentity user
  (entity-fields :id :username :notes))

(defentity hive
  (entity-fields :id :owner :name :notes))

(defentity sample
  (entity-fields :id :hive :timestamp)
  
  (transform (fn [sample]
               (assoc sample :datetime (coerce/from-long (:timestamp sample))))))

; User


(defn all-users
  []
  (select user))

(defn user-by-id
  [user-id]
  (first (select user (where {:id user-id}))))

(defn user-by-username
  [username]
  (first (select user (where {:username username}))))

; Hive

(defn all-hives
  []
  (select hive))

(defn hive-by-id [id]
  (first (select hive (where {:id id}))))

(defn hives-for-user
  [user-id]
  (select hive (where {:owner user-id})))

(defn hive-for-user
  [user-id hive-name]
  (first (select hive (where {:owner user-id :name hive-name}))))

; Recordings

(defn samples-for-hive
  [hive-id]
  (select sample (where {:hive hive-id})))

(defn earliest-sample-for-hive
  [hive-id]
  (first (select sample (where {:hive hive-id}) (order :timestamp :ASC))))

(defn latest-sample-for-hive
  [hive-id]
  (first (select sample (where {:hive hive-id}) (order :timestamp :DESC))))

(defn samples-between
  [hive-id start end]
  "Return only timestamp between start and end timestamps."
  (map :timestamp (select sample
          (where {:hive hive-id})
          (where (>= :timestamp start))
          (where (<= :timestamp end)))))

(defn insert-sample [hive-id timestamp]
  (insert sample (values {:hive hive-id :timestamp timestamp})))

(defn any-samples-for-hive-between [hive-id start end]
  (first (select sample
            (where {:hive hive-id})
            (where (>= :timestamp start))
            (where (<= :timestamp end))
            (limit 1))))

