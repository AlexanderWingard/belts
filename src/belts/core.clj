(ns belts.core
  (:require [clojure.core.async :refer [sliding-buffer onto-chan close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult tap]]))

(defn channel?
  [x]
  (satisfies? clojure.core.async.impl.protocols/Channel x))

(defn mult?
  [x]
  (satisfies? clojure.core.async/Mult x))

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)]
    (go-loop []
      (let [msg (<! in)
            msg-wo-from (dissoc msg ::from)
            return (if (some? cfg)
                     (meat msg-wo-from cfg)
                     (meat msg-wo-from))
            return-to (get msg ::from out)]
        (cond
          (channel? return) (pipe return return-to false)
          (some? return) (>! return-to return)))
      (recur))
    {:in in :out out}))

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
