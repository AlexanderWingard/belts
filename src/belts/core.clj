(ns belts.core
  (:require [clojure.core.async :refer [close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult tap]]))

(defn channel?
  [x]
  (satisfies? clojure.core.async.impl.protocols/Channel x))

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)]
    (go-loop []
      (let [msg (<! in)
            return (meat (merge cfg (dissoc msg ::from)))
            return-to (get msg ::from out)]
        (cond
          (channel? return) (pipe return return-to)
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

(defn graph [g]
  (doseq [[from to] g]
    (pipe (:out from) (:in to)))
  {:in (:in (first (first g)))
   :out (:out (last (last g)))})

(defn splitter [{:keys [in out]}]
  (let [m (mult out)]
    ((fn create-tap []
       {:in in
        :out (tap m (chan))
        :create-tap create-tap}))))

(defn rpc [compo args]
  (let [self (chan)
        msg (assoc args ::from self)]
    (put-with-timeout compo msg)
    (read-with-timeout {:out self})))
