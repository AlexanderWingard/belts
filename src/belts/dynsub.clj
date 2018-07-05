(ns belts.dynsub
  (:require
   [belts.core :refer [graph cloner component]]
   [clojure.core.async :refer [>!! untap]]
   [clojure.core.match :refer [match]]))

(defn ^:private dynsub-handle-sub [oldstate in compo topic init-compo]
  (update
   oldstate
   topic
   (fn [{:keys [pub subs] :as old}]
     (let [pub (if (nil? pub)
                 (let [pub (cloner)
                       meat (init-compo topic)]
                   (graph [[in meat pub]])
                   {:mult pub :meat meat})
                 pub)]
       (-> old
           (assoc :pub pub)
           (update :subs (fn [subs]
                           (graph [[(:mult pub) compo]])
                           (conj subs compo))))))))

(defn ^:private dynsub-handle-unsub [oldstate in compo]
  (reduce-kv
   (fn [m k v]
     (let [new-v (if (some #{compo} (:subs v))
                   (do
                     (untap (:out (:mult (:pub v))) (:in compo))
                     (update v :subs #(remove #{compo} %1)))
                   v)]
       (if (not-empty (:subs new-v))
         (assoc m k new-v)
         (do
           (untap (:out in) (:in (:meat (:pub new-v))))
           m))))
   {}
   oldstate))

(defn dynsub [init-compo]
  (component
   (let [state (atom {})
         in (cloner)]
     (fn [msg]
       (match msg
              {:cmd "sub" :compo compo :topic topic}
              (swap! state dynsub-handle-sub in compo topic init-compo)

              {:cmd "unsub" :compo compo}
              (swap! state dynsub-handle-unsub in compo)

              :else
              (>!! (:in in) msg))
       nil))))

(defn dynsub-sub [sh compo topic]
  (>!! (:in sh) {:cmd "sub" :compo compo :topic topic}))

(defn dynsub-unsub [sh compo]
  (>!! (:in sh) {:cmd "unsub" :compo compo}))
