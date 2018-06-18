(ns belts.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.match :refer [match]]
            [clojure.core.async :refer [onto-chan close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult]]
            [belts.core :refer :all]))

(defn times-m [{:keys [n]} {:keys [m]}]
  "A component that multiplies m and n"
  {:n (* m n)})

(defn state-adder [msg {:keys [state]}]
  "A component that keeps state and handles RPC"
  (match
   msg
    {:rpc "get"} {:n @state}
    {:rpc "set" :val v} {:n (reset! state v)}
    {:n n} {:n (+ @state n)}))

(defn accer [msg {:keys [state]}]
  (swap! state conj msg))

(defn multi-answer [msg]
  (multi-out [{:n 1} {:n 2}]))

(deftest compo-test
  (testing "components"
    (let [first  (component state-adder {:state (atom 10)})
          second (component times-m {:m 2})
          _  (graph [[first second]])
          v1 (rpc first {:rpc "get"})
          _  (rpc first {:rpc "set" :val 20})
          v2 (rpc first {:rpc "get"})
          _  (put-with-timeout first {:n 10})
          v3 (read-with-timeout second)]
      (is (= {:n 10} v1))    ;; Initial state
      (is (= {:n 20} v2))    ;; After set
      (is (= {:n 60} v3))))  ;; (20 + 10) * 2
  (testing "graph"
    (let [first  (component state-adder {:state (atom 10)})
          second (component times-m {:m 2})
          g (graph [[first second]])]
      (dotimes [n 3]
        (put-with-timeout g {:n 10}))
      (dotimes [n 3]
        (is (thrown? AssertionError (put-with-timeout g {:n 10}))))
      (mult (:out second))
      (dotimes [n 3]
        (put-with-timeout g {:n 10}))))
  (testing "multi answer"
    (let [ma (component multi-answer)]
      (put-with-timeout ma {})
      (is (= {:n 1} (read-with-timeout ma)))
      (is (= {:n 2} (read-with-timeout ma)))))
  (testing "cloner"
    (let [t (component times-m {:m 2})
          c (cloner)
          t1 (echo)
          t2 (echo)
          g (graph [[t c]
                    [c t1]
                    [c t2]])]
      (put-with-timeout g {:n 1})
      (is (= {:n 2} (read-with-timeout t1)))
      (is (= {:n 2} (read-with-timeout t2)))))
  (testing "dead end"
    (let [c1 (echo)
          c2 (echo)
          g (graph [[c1 c2]])]
      (put-with-timeout c1 {:n 10})
      (is (= {:n 10} (read-with-timeout c2)))
      (dead-end c1)
      (dotimes [n 30]
        (put-with-timeout c1 {:n 10}))))
  (testing "cloner at the end"
    (let [g (graph [[(echo) (cloner)]])]
      (dotimes [n 10]
        (put-with-timeout g {:n 10}))
      (let [g2 (graph [[g (echo)]])]
        (put-with-timeout g2 {:n 10})
        (is (= {:n 10} (read-with-timeout g2))))))
  (testing "thread graphs"
    (let [g (graph [[(echo) (component times-m {:m 2}) (echo)]])]
      (put-with-timeout g {:n 10})
      (is (= {:n 20} (read-with-timeout g)))))
  (testing "debouncer"
    (let [c (component (fn [msg] (multi-out (for [x (range 10)] {:n x}))))
          d (debouncer)
          a (component accer {:state (atom [])})
          g (graph [[c d a]])]
      (put-with-timeout g {})
      (is (= [{:n 0}] (read-with-timeout a)))
      (<!! (timeout 1000))
      (is (= [{:n 0} {:n 9}] (read-with-timeout a))))))
