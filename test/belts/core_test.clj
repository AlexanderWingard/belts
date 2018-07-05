(ns belts.core-test
  (:require
   [belts.core :refer :all]
   [clojure.core.async :refer [offer! onto-chan close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult] :as as]
   [clojure.test :refer :all]))

(defn times-m [{:keys [n]} {:keys [m]}]
  "A component that multiplies m and n"
  {:n (* m n)})

(defn state-adder [{:keys [rpc n]} {:keys [state]}]
  "A component that keeps state and handles RPC"
  (cond (= "get" rpc) {:n @state}
        (= "set" rpc) {:n (reset! state n)}
        :else {:n (+ @state n)}))

(defn accer [msg {:keys [state]}]
  (swap! state conj msg))

(defn multi-answer [msg]
  (multi-out [{:n 1} {:n 2}]))

(defn inc-meat [msg {:keys [state]}]
  {:n (swap! state inc)})

(defn inc-no-cfg []
  (component
   (let [state (atom 0)]
     (fn [msg]
       {:n (swap! state inc)}))))

(defn c-put [c msg]
  (>!! (:in c) msg))

(defn c-take [c]
  (<!! (:out c)))

(deftest fun-compo-test
  (let [c (inc-no-cfg)]
    (c-put c {})
    (is (= {:n 1} (c-take c)))
    (c-put c {})
    (is (= {:n 2} (c-take c)))))

(deftest compo-test
  (testing "graph"
    (let [first  (component state-adder {:state (atom 10)})
          second (component times-m {:m 2})
          g (graph [[first second]])]
      (c-put g {:n 10})
      (is (= {:n 40} (c-take g)))))
  (testing "multi answer"
    (let [ma (component multi-answer)]
      (c-put ma {})
      (is (= {:n 1} (c-take ma)))
      (is (= {:n 2} (c-take ma)))))
  (testing "cloner"
        (let [t (component times-m {:m 2})
              c (cloner)
              t1 (echo)
              t2 (echo)
              g (graph [[t c]
                        [c t1]
                        [c t2]])]
          (c-put g {:n 1})
          (is (= {:n 2} (c-take t1)))
          (is (= {:n 2} (c-take t2)))))
  (testing "dead end"
        (let [c1 (echo)
              c2 (echo)
              g (graph [[c1 c2]])]
          (c-put c1 {:n 10})
          (is (= {:n 10} (c-take c2)))
          (dotimes [n 3]
            (c-put c1 {:n 10}))
          (is (= nil (offer! (:in c1) {:n 10})))
          (dotimes [n 3]
            (c-take c2))
          (dead-end c2)
          (dotimes [n 9]
            (c-put c1 {:n 10}))))
  (testing "cloner at the end"
        (let [g (graph [[(echo) (cloner)]])]
          (dotimes [n 10]
            (c-put g {:n 10}))
          (let [g2 (graph [[g (echo)]])]
            (c-put g {:n 10})
            (is (= {:n 10} (c-take g2))))))
  (testing "thread graphs"
        (let [g (graph [[(echo) (component times-m {:m 2}) (echo)]])]
          (c-put g {:n 10})
          (is (= {:n 20} (c-take g)))))
  (testing "debouncer"
        (let [c (component (fn [msg] (multi-out (for [x (range 10)] {:n x}))))
              d (debouncer 1)
              g (graph [[c d]])]
          (c-put g {})
          (is (= {:n 0} (c-take g)))
          (is (= {:n 9} (c-take g)))))
  (testing "fun-cache"
    (let [c (component inc-meat {:state (atom 0)})
          fc (fun-cache c)]
      (c-put c {})
      (is (= {:n 1} (c-take c)))
      (c-put c {})
      (is (= {:n 2} (c-take c)))
      (c-put fc {})
      (is (= {:n 3} (c-take fc)))
      (c-put fc {})
      (is (= {:n 3} (c-take fc)))
      (c-put fc {:test 1})
      (is (= {:n 4} (c-take fc))))))
