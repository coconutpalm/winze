(ns llm-memory.ui.keybindings
  "Scoped keybinding system — focus-aware key dispatch with externalized bindings,
  prefix key support, and platform-correct modifier handling."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [hyperfiddle.rcf :refer [tests]]
   [llm-memory.ui.commands :as commands]
   [llm-memory.ui.resources :as resources])
  (:import
   [java.util.concurrent TimeUnit]
   [org.eclipse.swt SWT]
   [org.eclipse.swt.widgets Display]))

;; ---------------------------------------------------------------------------
;; Platform detection
;; ---------------------------------------------------------------------------

(def mac? resources/macos?)

;; ---------------------------------------------------------------------------
;; Focus scope via .setData("scope", :keyword)
;; ---------------------------------------------------------------------------

(defn active-scope
  "Return the keybinding scope keyword for the currently focused widget,
  or :global if no scope is set."
  []
  (if-let [focus (.getFocusControl (Display/getCurrent))]
    (let [scope (.getData focus "scope")]
      (if (keyword? scope) scope :global))
    :global))

;; ---------------------------------------------------------------------------
;; Active popup tracking
;; ---------------------------------------------------------------------------

(defonce active-popup (atom nil))

(defn set-active-popup!
  "Register an open popup type (e.g. :content-assist, :find-bar)."
  [popup-type]
  (reset! active-popup popup-type))

(defn clear-active-popup!
  "Deregister the current popup."
  []
  (reset! active-popup nil))

;; ---------------------------------------------------------------------------
;; Context building
;; ---------------------------------------------------------------------------

(defn build-context
  "Build the current keybinding context map for predicate evaluation.
  Called on the UI thread during key dispatch."
  []
  {:in           (active-scope)
   :active-popup @active-popup})

;; ---------------------------------------------------------------------------
;; Predicate evaluation + specificity
;; ---------------------------------------------------------------------------

(defn eval-when
  "Return true if the :when predicate map matches the given context.
  Each key in when-map must match the corresponding key in context.
  Values can be keywords (exact match) or sets (membership check).
  An empty when-map always matches."
  [when-map context]
  (every? (fn [[k v]]
            (let [actual (get context k)]
              (if (set? v)
                (contains? v actual)
                (= v actual))))
          when-map))

(defn specificity
  "Number of predicate keys in a :when map. Higher = more specific."
  [when-map]
  (count when-map))

;; ---------------------------------------------------------------------------
;; Special key mapping
;; ---------------------------------------------------------------------------

(def ^:private special-keys
  ;; SWT defines ESC, TAB, CR, BS, DEL as char constants — coerce to int
  ;; so they match (.keyCode event) which always returns int.
  {:esc       (int SWT/ESC)  :tab       (int SWT/TAB)  :enter     (int SWT/CR)
   :space     (int \space)   :backspace (int SWT/BS)    :delete    (int SWT/DEL)
   :insert    SWT/INSERT
   :f1 SWT/F1 :f2 SWT/F2 :f3 SWT/F3 :f4 SWT/F4
   :f5 SWT/F5 :f6 SWT/F6 :f7 SWT/F7 :f8 SWT/F8
   :f9 SWT/F9 :f10 SWT/F10 :f11 SWT/F11 :f12 SWT/F12
   :up    SWT/ARROW_UP    :down  SWT/ARROW_DOWN
   :left  SWT/ARROW_LEFT  :right SWT/ARROW_RIGHT
   :home  SWT/HOME        :end   SWT/END
   :page-up SWT/PAGE_UP   :page-down SWT/PAGE_DOWN})

(defn- resolve-key
  "Resolve a key specification to an SWT keyCode integer.
  Accepts characters, special-key keywords, or raw ints."
  [k]
  (cond
    (char? k)    (int k)
    (keyword? k) (get special-keys k)
    (int? k)     k))

;; ---------------------------------------------------------------------------
;; Modifier extraction from SWT events
;; ---------------------------------------------------------------------------

(defn- event->mod-set
  "Extract the modifier set from an SWT key event using platform-correct MOD1."
  [event]
  (let [mask (.stateMask event)]
    (cond-> #{}
      (not= 0 (bit-and mask SWT/MOD1))  (conj :mod1)
      (not= 0 (bit-and mask SWT/SHIFT)) (conj :shift)
      (not= 0 (bit-and mask SWT/ALT))   (conj :alt))))

;; ---------------------------------------------------------------------------
;; Index builder
;; ---------------------------------------------------------------------------

(defn- build-index
  "Build keybinding index from a flat list of binding maps.
  Returns {:normal  {[mod-set keyCode] -> [binding ...]}
           :prefix  {[mod-set keyCode] -> true}
           :prefixed {[prefix-mod prefix-key mod-set keyCode] -> [binding ...]}}."
  [bindings]
  (reduce
   (fn [idx {:keys [key mod prefix command when]
             :or   {mod #{} when {}}}]
     (let [kc (resolve-key key)]
       (when-not kc
         (log/warn "Unresolvable key in binding:" key))
       (if (and kc prefix)
          ;; Prefixed binding (two-key sequence)
         (let [pkc  (resolve-key (:key prefix))
               pmod (or (:mod prefix) #{})]
           (-> idx
               (assoc-in [:prefix [pmod pkc]] true)
               (update-in [:prefixed [pmod pkc mod kc]] (fnil conj [])
                          {:command command :when when})))
         (if kc
            ;; Normal binding
           (update-in idx [:normal [mod kc]] (fnil conj [])
                      {:command command :when when})
           idx))))
   {:normal {} :prefix {} :prefixed {}}
   bindings))

;; ---------------------------------------------------------------------------
;; Keybinding loading
;; ---------------------------------------------------------------------------

(defonce ^:private bindings-index (atom {:normal {} :prefix {} :prefixed {}}))

(defn- load-keybinding-file
  "Load a single .keybinding file. Returns its bindings vector or nil."
  [file]
  (try
    (let [bindings (edn/read-string (slurp file))]
      (when (vector? bindings)
        (log/info "Loaded keybindings from" (.getName file))
        bindings))
    (catch Exception e
      (log/error e "Failed to load keybindings from" (.getName file))
      nil)))

(defn load-keybindings!
  "Load all .keybinding files from the given directories (in order).
  Replaces the current index with the loaded bindings.
  Skipped if no directories contain .keybinding files."
  [& dirs]
  (let [all-bindings (into []
                           (mapcat
                            (fn [dir]
                              (when dir
                                (let [d (io/file dir)]
                                  (when (and d (.isDirectory d))
                                    (mapcat (fn [file]
                                              (when (str/ends-with? (.getName file) ".keybinding")
                                                (load-keybinding-file file)))
                                            (sort (.listFiles d))))))))
                           dirs)]
    (when (seq all-bindings)
      (reset! bindings-index (build-index all-bindings))
      (log/info "Keybinding index built:" (count (:normal @bindings-index)) "normal,"
                (count (:prefix @bindings-index)) "prefix,"
                (count (:prefixed @bindings-index)) "prefixed entries"))))

(defn register-bindings!
  "Register keybindings from a vector of binding maps (programmatic API).
  Merges into the existing index."
  [bindings]
  (let [new-idx (build-index bindings)]
    (swap! bindings-index
           (fn [old]
             {:normal   (merge-with into (:normal old) (:normal new-idx))
              :prefix   (merge (:prefix old) (:prefix new-idx))
              :prefixed (merge-with into (:prefixed old) (:prefixed new-idx))}))))

(defn load-classpath-keybinding!
  "Load a single .keybinding resource from the classpath (works inside JARs).
  `resource-path` is e.g. \"keybindings/default.keybinding\"."
  [resource-path]
  (when-let [url (io/resource resource-path)]
    (try
      (let [bindings (edn/read-string (slurp url))]
        (when (vector? bindings)
          (register-bindings! bindings)
          (log/info "Loaded keybindings from classpath:" resource-path)))
      (catch Exception e
        (log/error e "Failed to load keybindings from classpath:" resource-path)))))

;; ---------------------------------------------------------------------------
;; Prefix state machine
;; ---------------------------------------------------------------------------

(defonce ^:private prefix-state
  (atom {:prefix nil :timer nil}))

(defn- enter-prefix-mode!
  "Record the prefix and start the timeout."
  [prefix-mod prefix-kc]
  (when-let [old (:timer @prefix-state)]
    (.cancel old false))
  (let [timer (.schedule resources/executor
                         ^Runnable (fn [] (reset! prefix-state {:prefix nil :timer nil}))
                         (long 1500) TimeUnit/MILLISECONDS)]
    (reset! prefix-state {:prefix [prefix-mod prefix-kc] :timer timer})))

(defn- exit-prefix-mode!
  "Clear prefix state and cancel timeout."
  []
  (when-let [timer (:timer @prefix-state)]
    (.cancel timer false))
  (reset! prefix-state {:prefix nil :timer nil}))

(defn in-prefix-mode?
  "Return true if a prefix key has been pressed and we're waiting for the second key."
  []
  (some? (:prefix @prefix-state)))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn- best-match
  "Select the highest-specificity binding that matches the given context."
  [candidates ctx]
  (->> candidates
       (filter #(eval-when (:when %) ctx))
       (sort-by #(specificity (:when %)))
       last))

(defn- dispatch-normal!
  "Try to dispatch a normal (non-prefixed) key event. Returns true if handled."
  [idx mod-set kc ctx]
  (when-let [candidates (get-in idx [:normal [mod-set kc]])]
    (when-let [match (best-match candidates ctx)]
      (commands/execute! (:command match))
      true)))

(defn dispatch-key!
  "Look up a key event in the keybinding index. Returns true if handled.
  Must be called on the UI thread."
  [event]
  (let [mod-set (event->mod-set event)
        kc      (.keyCode event)
        ctx     (build-context)
        idx     @bindings-index
        pstate  @prefix-state]

    (if-let [[pmod pkc] (:prefix pstate)]
      ;; In prefix mode — look for prefixed binding
      (do
        (exit-prefix-mode!)
        (let [candidates (get-in idx [:prefixed [pmod pkc mod-set kc]])]
          (if-let [match (best-match candidates ctx)]
            (do (commands/execute! (:command match)) true)
            ;; No prefixed match — fall through to normal dispatch
            (dispatch-normal! idx mod-set kc ctx))))

      ;; Not in prefix mode
      (if (get-in idx [:prefix [mod-set kc]])
        ;; This key is a prefix trigger
        (do (enter-prefix-mode! mod-set kc) true)
        ;; Normal dispatch
        (dispatch-normal! idx mod-set kc ctx)))))

;; ---------------------------------------------------------------------------
;; Platform-correct keybinding hints (for palette display)
;; ---------------------------------------------------------------------------

(def ^:private mod-symbols
  (if mac?
    {:mod1 "\u2318" :shift "\u21E7" :alt "\u2325"}
    {:mod1 "Ctrl+" :shift "Shift+" :alt "Alt+"}))

(def ^:private mod-order [:mod1 :alt :shift])

(defn- format-key
  "Format a key spec as a display string."
  [k]
  (cond
    (keyword? k) (str/upper-case (name k))
    (char? k)    (str/upper-case (str k))
    :else        (str k)))

(defn format-hint
  "Format a keybinding as a display string. E.g., '⌘B' or 'Ctrl+B'."
  [{:keys [key mod prefix]}]
  (let [fmt-one (fn [m k]
                  (let [mods (filter (or m #{}) mod-order)]
                    (str (str/join (map mod-symbols mods))
                         (format-key k))))]
    (if prefix
      (str (fmt-one (:mod prefix) (:key prefix))
           " " (fmt-one mod key))
      (fmt-one mod key))))

(def ^:private reverse-special-keys
  "Map from SWT keyCode back to the keyword used in binding specs."
  (into {} (map (fn [[k v]] [v k]) special-keys)))

(defn- scope-hint
  "Build a display-string scope hint from a `:in` value.
  Accepts nil, a keyword (single scope), or a set of keywords (multi-scope)."
  [in]
  (cond
    (nil? in)      ""
    (keyword? in)  (name in)
    (set? in)      (str/join "," (map name (sort in)))
    :else          (str in)))

(defn hint-index
  "Build a map of command-id -> {:hint \"⌘B\" :scope \"editor\"}.
  Reverse-looks up normal bindings to find the first keybinding for each command."
  []
  (reduce-kv
   (fn [acc [mod-set kc] candidates]
     (reduce (fn [acc {:keys [command when]}]
               (if (contains? acc command)
                 acc
                 (let [key-spec (or (get reverse-special-keys kc)
                                    (char kc))
                       scope    (scope-hint (:in when))]
                   (assoc acc command {:hint  (format-hint {:key key-spec :mod mod-set})
                                       :scope scope}))))
             acc candidates))
   {}
   (:normal (deref bindings-index))))

;; ---------------------------------------------------------------------------
;; RCF tests
;; ---------------------------------------------------------------------------

(tests
 ;; eval-when basics
 (eval-when {} {:in :editor})                               := true
 (eval-when {:in :editor} {:in :editor})                    := true
 (eval-when {:in :editor} {:in :viewer})                    := false
 (eval-when {:in #{:editor :viewer}} {:in :editor})         := true
 (eval-when {:in :editor :active-popup :content-assist}
            {:in :editor :active-popup :content-assist})    := true
 (eval-when {:in :editor :active-popup :content-assist}
            {:in :editor :active-popup nil})                := false

 ;; specificity
 (specificity {})                                           := 0
 (specificity {:in :editor})                                := 1
 (specificity {:in :editor :active-popup :content-assist})  := 2

 ;; resolve-key
 (resolve-key \a)                                           := (int \a)
 (resolve-key :esc)                                         := (int SWT/ESC)
 (resolve-key 42)                                           := 42

 ;; build-index
 (let [idx (build-index [{:key \e :mod #{:mod1} :command :toggle-mode}
                         {:key :esc :command :escape :when {:in :editor}}])]
   [(count (get-in idx [:normal [#{:mod1} (int \e)]]))
    (count (get-in idx [:normal [#{} (int SWT/ESC)]]))])
 := [1 1]

 ;; build-index with prefix
 (let [idx (build-index [{:prefix {:key \x :mod #{:mod1}}
                          :key \s :mod #{:mod1}
                          :command :save}])]
   [(get-in idx [:prefix [#{:mod1} (int \x)]])
    (count (get-in idx [:prefixed [#{:mod1} (int \x) #{:mod1} (int \s)]]))])
 := [true 1]

 ;; best-match selects most specific
 (let [candidates [{:command :general :when {:in :editor}}
                   {:command :specific :when {:in :editor :active-popup :ca}}]
       ctx {:in :editor :active-popup :ca}]
   (:command (best-match candidates ctx)))
 := :specific

 ;; format-hint (platform-dependent, test shape not exact string)
 (string? (format-hint {:key \b :mod #{:mod1}})) := true

 :rcf)
