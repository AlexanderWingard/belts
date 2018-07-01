(ns belts.core
  (:require [clojure.core.async :refer [sliding-buffer onto-chan close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult tap]]
            [clojure.spec.alpha :as s]))

(defn channel?
  [x]
  (satisfies? clojure.core.async.impl.protocols/Channel x))

(defn mult?
  [x]
  (satisfies? clojure.core.async/Mult x))

(s/def ::chan channel?)
(s/def ::mult mult?)
(s/def ::port (s/or :chan ::chan
                    :mult ::mult))
(s/def ::in ::chan)
(s/def ::out ::port)
(s/def ::component (s/keys :req-un [::in ::out]))
(s/def ::msg-in map?)
(s/def ::msg-out (s/or :map map?
                       :chan ::chan
                       :nil nil?))

(defn validate [spec val & extra]
  (if-not (s/valid? spec val)
    (throw (AssertionError. (str (s/explain-str spec val) extra)))))

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)]
    (go-loop []
      (when-let [msg (<! in)]
        (validate ::msg-in msg meat)
        (let [msg-wo-from (dissoc msg ::from)
              return (if (some? cfg)
                       (meat msg-wo-from cfg)
                       (meat msg-wo-from))
              return-to (get msg ::from out)]
          (validate ::msg-out return meat)
          (cond
            (channel? return) (pipe return return-to false)
            (some? return) (>! return-to return)))
        (recur)))
    {:in in :out out}))

(defn shutdown [{:keys [in out] :as c}]
  (close! in)
  (close! out)
  c)

(defn throw-timeout []
  (throw (AssertionError. "Timeout")))

(defn read-with-timeout [compo]
  (let [out (:out compo)
        [value channel] (alts!! [out (timeout 10)])]
    (if (= channel out)
      value
      (throw-timeout))))

(defn put-with-timeout [compo v]
  (let [out (go (>! (:in compo) v))
        [value channel] (alts!! [out (timeout 10)])]
    (if (not= channel out)
      (throw-timeout))))

(defn give-coll-to-component [compo coll]
  (<!! (onto-chan (:in compo) coll false)))

(defn graph [g]
  (doseq [thread g
          [from to] (partition 2 1 thread)]
    (validate ::component from)
    (validate ::component to)
    ((if (mult? (:out from)) tap pipe) (:out from) (:in to) false))
  {:in (:in (first (first g))) :out (:out (last (last g)))})

(defn dead-end [{:keys [out] :as c}]
  (close! out)
  c)

(defn printer []
  (component (fn [msg] (println msg) msg)))

(defn cloner []
  (let [in (chan)
        m (mult in)]
    {:in in :out m}))

(defn echo []
  (let [c (chan)]
    {:in c :out c}))

(defn debouncer [interval]
  (let [in (chan (sliding-buffer 1))
        out (chan)]
    (go-loop []
      (>! out (<! in))
      (<! (timeout interval))
      (recur))
    {:in in :out out}))

(defn ticker [interval]
  (let [c (chan)]
    (go-loop [n 0]
      (>! c {:tick n})
      (<! (timeout interval))
      (recur (inc n)))
    {:in c :out c}))

(defn rpc [compo args]
  (let [self (chan)
        msg (assoc args ::from self)]
    (put-with-timeout compo msg)
    (read-with-timeout {:out self})))

(defn multi-out [coll]
  (let [out (chan)]
    (onto-chan out coll)
    out))
