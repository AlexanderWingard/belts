(ns factorio.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.match :refer [match]]
            [clojure.core.async :refer [close! pipe chan <!! <! >! >!! timeout alts! alts!! go-loop go mult]]
            [factorio.core :refer :all]))

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

(defn component [meat & [cfg]]
  (let [in (chan)
        out (chan)
        backdoor (chan)]
    (go-loop []
      (let [[msg c] (alts! [in backdoor])
            return-to (if (= c in) out (:from msg))]
        (when-let [return (meat (merge cfg msg))]
          (>! return-to return)))
      (recur))
    {:in in :out out :backdoor backdoor}))

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
        msg (assoc args :from self)
        _ (>!! (:backdoor compo) msg)]
    (<!! self)))

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
      (put-with-timeout first {:n 10})
      (put-with-timeout first {:n 10})
      (put-with-timeout first {:n 10})
      (is (thrown? AssertionError (put-with-timeout first {:n 10})))
      (is (thrown? AssertionError (put-with-timeout first {:n 10})))
      (mult (:out second))
      (put-with-timeout first {:n 10}))))
