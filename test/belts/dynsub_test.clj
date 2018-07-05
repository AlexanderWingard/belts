(ns belts.dynsub-test
  (:require [belts.dynsub :refer :all]
            [belts.core :refer :all]
            [clojure.core.async :refer [poll!]]
            [clojure.set :refer [rename-keys]]
            [clojure.test :refer :all]))

(defn key-renamer [kmap]
  (component
   (fn [msg]
     (rename-keys msg kmap))))

(deftest dynsub-test
  (let [c (dynsub key-renamer)
        e1 (echo)
        e2 (echo)
        e3 (echo)]
    (dynsub-sub c e1 {:n :a})
    (dynsub-sub c e2 {:n :a})
    (dynsub-sub c e3 {:n :b})
    (put-with-timeout c {:n 1})
    (is (= {:a 1} (read-with-timeout e1)))
    (is (= {:a 1} (read-with-timeout e2)))
    (is (= {:b 1} (read-with-timeout e3)))

    (dynsub-unsub c e1)
    (put-with-timeout c {:n 2})
    (is (nil? (poll! (:out e1))))
    (is (= {:a 2} (read-with-timeout e2)))
    (is (= {:b 2} (read-with-timeout e3)))

    (dynsub-unsub c e1)
    (dynsub-unsub c e2)
    (put-with-timeout c {:n 3})
    (is (nil? (poll! (:out e1))))
    (is (nil? (poll! (:out e2))))
    (is (= {:b 3} (read-with-timeout e3)))

    (dynsub-unsub c e3)
    (put-with-timeout c {:n 4})
    (is (nil? (poll! (:out e1))))
    (is (nil? (poll! (:out e2))))
    (is (nil? (poll! (:out e3))))))
