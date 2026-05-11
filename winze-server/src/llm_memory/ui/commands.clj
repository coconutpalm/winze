(ns llm-memory.ui.commands
  "Command registry — named actions that the palette and keybindings both reference.
  Commands have no hotkey field; keybindings are external."
  (:require
   [clojure.tools.logging :as log]
   [hyperfiddle.rcf :refer [tests]]))

(defonce registry (atom (sorted-map)))

(defn register!
  "Register a command. `cmd` is a map with :id, :label, :category, :action."
  [cmd]
  {:pre [(:id cmd) (:label cmd) (:action cmd)]}
  (swap! registry assoc (:id cmd) cmd))

(defn unregister!
  "Remove a command by ID. Used for per-editor-instance commands on dispose."
  [id]
  (swap! registry dissoc id))

(defn lookup
  "Return the command definition for the given ID, or nil."
  [id]
  (get @registry id))

(defn execute!
  "Execute the command with the given ID. Returns nil if not found."
  [id]
  (if-let [{:keys [action label]} (get @registry id)]
    (try
      (action)
      (catch Throwable t
        (log/error t "Command execution failed:" label)
        nil))
    (log/warn "Unknown command:" id)))

(defn list-commands
  "Return all registered commands, optionally filtered by category."
  ([] (vals @registry))
  ([category] (filter #(= category (:category %)) (vals @registry))))

(tests
 ;; register + lookup
 (do (register! {:id :test/hello :label "Hello" :category :test
                 :action (fn [] :ok)})
     (:id (lookup :test/hello)))
 := :test/hello

 ;; execute
 (execute! :test/hello) := :ok

 ;; list-commands
 (some #(= :test/hello (:id %)) (list-commands)) := true
 (some #(= :test/hello (:id %)) (list-commands :test)) := true
 (some #(= :test/hello (:id %)) (list-commands :nonexistent)) := nil

 ;; unregister
 (do (unregister! :test/hello) (lookup :test/hello)) := nil
 :rcf)
