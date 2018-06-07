(ns factorio.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan <! >! >!! timeout alts!! go-loop go]]
            [factorio.core :refer :all]))

(defn plus-one [{:keys [n]}]
  (+ n 1))

(defn times-n [{:keys [m n]}]
  (* m n))

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)]
    (go-loop []
      (when-let [msg (<! in)]
        (>! out (meat (merge cfg msg)))
        (recur)))
    {:in in :out out}))

(defn read-with-timeout [c]
  (let [[value channel] (alts!! [c (timeout 1000)])]
    (if (= channel c)
      value
      (throw (AssertionError. "Timeout")))))

(deftest commponent-test
  (testing "components"
    (let [{p-in :in
           p-out :out} (component plus-one)
          {t-in :in
           t-out :out} (component times-n {:m 5})
          _ (>!! p-in {:n 1})
          added (read-with-timeout p-out)
          _ (>!! t-in {:n added})
          multed (read-with-timeout t-out)]
      (is (= 2 added))
      (is (= 10 multed)))))
