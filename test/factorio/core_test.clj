(ns factorio.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [pipe chan <! >! >!! timeout alts!! go-loop go]]
            [factorio.core :refer :all]))

(defn plus-one [{:keys [n]}]
  {:n (+ n 1)})

(defn times-n [{:keys [m n]}]
  {:n (* m n)})

(defn state-component [{:keys [state]}]
  {:n (swap! state inc)})

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)]
    (go-loop []
      (when-let [msg (<! in)]
        (>! out (meat (merge cfg msg)))
        (recur)))
    {:in in :out out}))

(defn read-with-timeout [c]
  (let [out (:out c)
        [value channel] (alts!! [out (timeout 1000)])]
    (if (= channel out)
      value
      (throw (AssertionError. "Timeout")))))

(defn give-component [c v]
  (>!! (:in c) v))

(defn connect [c1 c2]
  (pipe (:out c1) (:in c2)))

(deftest commponent-test
  (testing "components"
    (let [p (component plus-one)
          t (component times-n {:m 5})
          _ (give-component p {:n 1})
          _ (connect p t)
          multed (read-with-timeout t)]
      (is (= {:n 10} multed))))
  (testing "stateful component"
    (let [c (component state-component {:state (atom 0)})
          _ (give-component c {})
          s1 (read-with-timeout c)
          _ (give-component c {})
          s2 (read-with-timeout c)]
      (is (= {:n 1} s1))
      (is (= {:n 2} s2)))))
