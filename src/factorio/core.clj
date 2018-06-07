(ns factorio.core
  (:require [clojure.core.async :refer [chan <! >! go go-loop]])
  (:gen-class))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
