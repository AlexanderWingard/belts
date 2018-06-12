(ns belts.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.match :refer [match]]
            [clojure.core.async :refer [onto-chan close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult]]
            [belts.core :refer :all]))

(defn times-n [{:keys [m n]}]
  "A component that multiplies m and n"
  {:n (* m n)})

(defn state-adder [{:keys [state] :as msg}]
  "A component that keeps state and handles RPC"
  (match
   msg
    {:rpc "get"} {:n @state}
    {:rpc "set" :val v} {:n (reset! state v)}
    {:n n} {:n (+ @state n)}))

(defn multi-answer [msg]
  (let [result (chan)]
    (onto-chan result [{:n 1} {:n 2}])
    result))

(deftest compo-test
  (testing "components"
    (let [first  (component state-adder {:state (atom 10)})
          second (component times-n {:m 2})
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
          second (component times-n {:m 2})
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
      (is (= {:n 2} (read-with-timeout ma))))))
