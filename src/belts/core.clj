(ns belts.core
  (:require [clojure.core.async :refer [close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult]]))

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)]
    (go-loop []
      (let [msg (<! in)
            return (meat (merge cfg msg))
            return-to (get msg ::from out)]
        (when (some? return)
          (>! return-to return)))
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
  g)

(defn rpc [compo args]
  (let [self (chan)
        msg (assoc args ::from self)]
    (put-with-timeout compo msg)
    (read-with-timeout {:out self})))
