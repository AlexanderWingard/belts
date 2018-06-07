(ns factorio.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.match :refer [match]]
            [clojure.core.async :refer [pipe chan <!! <! >! >!! timeout alts!! go-loop go]]
            [factorio.core :refer :all]))

(defn plus-one [{:keys [n]}]
  {:n (+ n 1)})

(defn times-n [{:keys [m n]}]
  {:n (* m n)})

(defn state-component [{:keys [state]}]
  {:n (swap! state inc)})

(defn rpc-able [{:keys [state] :as msg}]
  (match [msg]
         [{:rpc "get" :from from}] (>!! from @state)
         [{:rpc "set" :from from :val v}] (>!! from (reset! state v)))
  nil)

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)]
    (go-loop []
      (when-let [msg (<! in)]
        (when-let [return (meat (merge cfg msg))]
          (>! out return))
        (recur)))
    {:in in :out out}))

(defn read-with-timeout [c]
  (let [out (:out c)
        [value channel] (alts!! [out (timeout 10)])]
    (if (= channel out)
      value
      (throw (AssertionError. "Timeout")))))

(defn give-component [c v]
  (>!! (:in c) v))

(defn connect [c1 c2]
  (pipe (:out c1) (:in c2)))

(defn rpc [c rpc & [extra]]
  (let [self (chan)
        msg (merge {:rpc rpc :from self} extra)
        _ (give-component c msg)]
    (<!! self)))

(deftest commponent-test
  (testing "components"
    (let [p (component plus-one)
          t (component times-n {:m 5})
          _ (give-component p {:n 1})
          _ (connect p t)
          multed (read-with-timeout t)]
      (is (= {:n 10} multed))))

  (testing "stateful component"
    (let [c  (component state-component {:state (atom 0)})
          _  (give-component c {})
          s1 (read-with-timeout c)
          _  (give-component c {})
          s2 (read-with-timeout c)]
      (is (= {:n 1} s1))
      (is (= {:n 2} s2))))

  (testing "rpc-able component"
    (let [self (chan)
          c  (component rpc-able {:state (atom "red")})
          v1 (rpc c "get")
          _  (rpc c "set" {:val "blue"})
          v2 (rpc c "get")]
      (is (= "red" v1))
      (is (= "blue" v2))
      (is (thrown? AssertionError (read-with-timeout c))))))
