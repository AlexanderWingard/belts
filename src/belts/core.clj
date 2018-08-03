(ns belts.core
  (:require [clojure.core.async :refer [sliding-buffer onto-chan close! pipe chan <!! <! >! >!! timeout alts! alts!! poll! go-loop go mult tap]]
            [clojure.spec.alpha :as s]))

(defn channel?
  [x]
  (satisfies? clojure.core.async.impl.protocols/Channel x))

(defn mult?
  [x]
  (satisfies? clojure.core.async/Mult x))

(defn uuid [] (str (java.util.UUID/randomUUID)))

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

(defonce nodes (atom {}))
(def nodes-dist (chan))
(def nodes-mult (mult nodes-dist))

(add-watch nodes :node-watcher (fn [k a o-s n-s]
                                 (go (>! nodes-dist {:inspector
                                                     (map #(select-keys (second %1) [:id :state :messages :source :target]) n-s)}))))

(def nodes-compo {:in nodes-dist :out nodes-mult})

(defn blinker [id]
  (let [c (chan)]
    (go-loop [in c t-o (chan)]
      (let [[msg from] (alts! [in t-o])]
        (cond
          (= "active" msg)
          (do
            (swap! nodes assoc-in [id :state] "active")
            (recur in (chan)))

          (= "idle" msg)
          (do
            (recur in (timeout 3000)))

          (= from t-o)
          (do
            (println id "idle")
            (swap! nodes assoc-in [id :state] "idle")
            (recur in (chan))))))
    c))


(defn add-node [data]
  (swap! nodes update (:id data) merge data))

(defn remove-node [id]
  (swap! nodes dissoc id))

(defn inc-msg [id]
  (swap! nodes update-in [id :messages] inc))

(defn change-state [id state]
  (when-some [chan (get-in @nodes [id :blinker])]
    (go (>! chan state))))

(defn validate [spec val & extra]
  (if-not (s/valid? spec val)
    (throw (AssertionError. (str (s/explain-str spec val) extra)))))

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)
        id (uuid)]
    (go-loop []
      (if-let [msg (<! in)]
        (do
          (validate ::msg-in msg meat)
          (change-state id "active")
          (inc-msg id)
          (let [msg-wo-from (dissoc msg ::from)
                return (meat msg-wo-from)
                return-to (get msg ::from out)]
            (validate ::msg-out return meat)
            (cond
              (channel? return) (pipe return return-to false)
              (some? return) (>! return-to return)))
          (change-state id "idle")
          (recur))
        (do
          (when (:pass-close cfg)
            (meat nil))
          (close! out)
          (remove-node id))))
    (add-node {:id id :info (str meat) :messages 0 :blinker (blinker id)})
    {:id id :in in :out out}))

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

(defn bpipe [from to]
  (let [in (chan)
        id (uuid)]
    ((if (mult? (:out from)) tap pipe) (:out from) in true)
    (add-node {:id id :source (:id from) :target (:id to) :messages -1})
    (go-loop []
      (if-let [msg (<! in)]
        (do
          (>! (:in to) msg)
          (recur))
        (do
          (remove-node id)
          (close! (:in to)))))))

(defn graph [g]
  (doseq [thread g
          [from to] (partition 2 1 thread)]
    (validate ::component from)
    (validate ::component to)
    (bpipe from to))
  {:id (uuid) :in (:in (first (first g))) :out (:out (last (last g)))})

(defn dead-end [{:keys [out] :as c}]
  (close! out)
  c)

(defn printer []
  (component (fn [msg] (println (apply str (take 100 (str msg)))) msg)))

(defn cloner []
  (let [in (chan)
        m (mult in)
        id (uuid)]
    {:id id :in in :out m}))

(defn echo []
  (component identity))

(defn debouncer [interval]
  (let [in (chan (sliding-buffer 1))
        out (chan)
        id (uuid)]
    (go-loop []
      (if-let [msg (<! in)]
        (do
          (>! out msg)
          (<! (timeout interval))
          (recur))
        (close! out)))
    {:id id :in in :out out}))

(defn stabilizer [interval]
  (let [in (chan (sliding-buffer 1))
        out (chan)
        id (uuid)]
    (go-loop [msg (<! in)]
      (if (some? msg)
        (do
          (<! (timeout interval))
          (if-some [new-msg (poll! in)]
            (recur new-msg)
            (do
              (>! out msg)
              (recur (<! in)))))
        (close! out)))
    {:id id :in in :out out}))

(defn slider []
  (let [c (chan (sliding-buffer 1))]
    {:id (uuid) :in c :out c}))

(defn ticker [interval]
  (let [in (chan)
        out (chan)
        id (uuid)]
    (go-loop [n 0]
      (>! out {:tick n})
      (let [t-o (timeout interval)
            [val from] (alts! [in t-o])]
        (if (= from t-o)
          (recur (inc n))
          (close! out))))
    {:id id :in in :out out}))

(defn rpc [compo args]
  (let [self (chan)
        msg (assoc args ::from self)]
    (put-with-timeout compo msg)
    (read-with-timeout {:out self})))

(defn multi-out [coll]
  (let [out (chan)]
    (onto-chan out coll)
    out))

(defn fun-cache [compo]
  (component
   (let [cache (atom {})]
     (fn [msg]
       (if-some [m msg]
         (if (= msg (:msg @cache))
           (:res @cache)
           (let [res (rpc compo msg)]
             (reset! cache {:msg msg :res res})
             res))
         (shutdown compo))))
   {:pass-close true}))
