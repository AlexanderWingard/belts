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
      (if-let [msg (<! in)]
        (do
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
          (recur))
        (close! out)))
    {:in in :out out}))

(defn shutdown [{:keys [in out] :as c}]
  (close! in)
  (close! out)
  c)

(defn throw-timeout []
  (throw (AssertionError. "Timeout")))

(defn read-with-timeout [compo]
  (let [out (:out compo)
        [value channel] (alts!! [out (timeout 1500)])]
    (if (= channel out)
      value
      (throw-timeout))))

(defn put-with-timeout [compo v]
  (let [out (go (>! (:in compo) v))
        [value channel] (alts!! [out (timeout 1500)])]
    (if (not= channel out)
      (throw-timeout))))

(defn give-coll-to-component [compo coll]
  (<!! (onto-chan (:in compo) coll false)))

(defn graph [g]
  (doseq [thread g
          [from to] (partition 2 1 thread)]
    (validate ::component from)
    (validate ::component to)
    ((if (mult? (:out from)) tap pipe) (:out from) (:in to) true))
  {:in (:in (first (first g))) :out (:out (last (last g)))})

(defn dead-end [{:keys [out] :as c}]
  (close! out)
  c)

(defn printer []
  (component (fn [msg] (println (apply str (take 100 (str msg)))) msg)))

(defn cloner []
  (let [in (chan)
        m (mult in)]
    {:in in :out m}))

(defn echo []
  (component identity))

(defn debouncer [interval]
  (let [in (chan (sliding-buffer 1))
        out (chan)]
    (go-loop []
      (if-let [msg (<! in)]
        (do
          (>! out msg)
          (<! (timeout interval))
          (recur))
        (close! out)))
    {:in in :out out}))

(defn slider []
  (let [c (chan (sliding-buffer 1))]
    {:in c :out c}))

(defn ticker [interval]
  (let [in (chan)
        out (chan)]
    (go-loop [n 0]
      (>! out {:tick n})
      (let [t-o (timeout interval)
            [val from] (alts! [in t-o])]
        (if (= from t-o)
          (recur (inc n))
          (close! out))))
    {:in in :out out}))

(defn rpc [compo args]
  (let [self (chan)
        msg (assoc args ::from self)]
    (put-with-timeout compo msg)
    (read-with-timeout {:out self})))

(defn multi-out [coll]
  (let [out (chan)]
    (onto-chan out coll)
    out))

(defn fun-cache-meat [msg {:keys [compo cache]}]
  (if (= msg (:msg @cache))
    (:res @cache)
    (let [res (rpc compo msg)]
      (reset! cache {:msg msg :res res})
      res)))

(defn fun-cache [c]
  (component fun-cache-meat {:compo c :cache (atom {})}))
