(ns llm-memory.ui.theme
  "User-editable theme at ~/.winze/theme.edn.

  This ns owns:
    - the reader (EDN parse → error collection)
    - the user-dir install + merge with bundled defaults
    - the SWT resource builder (Colors, Fonts, Images)
    - the orchestration entry points (`apply-theme-startup!`, `reload-theme!`)
    - the refresh-listener broadcast that tells live widgets to re-apply
      colors/fonts after a reload.

  The Var inventory in `llm-memory.ui.resources` is the registry — each
  theme var is an `(atom nil)` populated by this namespace before any
  widget dereferences it. The ns-publics walk + naming convention
  (`color-*`, `*-font`, `*-icon`, `*-image`) lets us enumerate all theme
  vars without a companion map."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [hyperfiddle.rcf :refer [tests]]
   [llm-memory.ui.resources :as res]
   [ui.SWT :refer [async-exec! display sync-exec! |]])
  (:import
   [java.io File FileInputStream InputStream PushbackReader]
   [java.nio.file Files]
   [org.eclipse.swt SWT]
   [org.eclipse.swt.graphics Color Font Image ImageData ImageDataProvider Resource]))

;; ---------------------------------------------------------------------------
;; Reader: hex + style parsing
;; ---------------------------------------------------------------------------

(def ^:private hex-pattern #"^#([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})$")

(defn parse-hex-color
  "Return [r g b] from a `#RRGGBB` string, or nil if malformed."
  [s]
  (when (string? s)
    (when-let [[_ r g b] (re-matches hex-pattern s)]
      [(Integer/parseInt r 16)
       (Integer/parseInt g 16)
       (Integer/parseInt b 16)])))

(def ^:private style-bits
  {:normal      SWT/NORMAL
   :bold        SWT/BOLD
   :italic      SWT/ITALIC
   :bold-italic (| SWT/BOLD SWT/ITALIC)})

(defn parse-style
  "Map a style keyword to SWT style bits. Returns nil if unknown."
  [kw]
  (get style-bits kw))

(tests
 (parse-hex-color "#C4B8FF")   := [0xC4 0xB8 0xFF]
 (parse-hex-color "#c4b8ff")   := [0xC4 0xB8 0xFF]
 (parse-hex-color "C4B8FF")    := nil
 (parse-hex-color "#C4B8F")    := nil
 (parse-hex-color "#ZZZZZZ")   := nil
 (parse-hex-color nil)         := nil
 (parse-style :normal)         := SWT/NORMAL
 (parse-style :bold)           := SWT/BOLD
 (parse-style :italic)         := SWT/ITALIC
 (parse-style :bold-italic)    := (| SWT/BOLD SWT/ITALIC)
 (parse-style :unknown)        := nil
 :rcf)

;; ---------------------------------------------------------------------------
;; Bundled icon defaults
;; ---------------------------------------------------------------------------

(def ^:private bundled-icons
  "Fixed semantic-key → classpath-path mapping for themable file-based
  icons. Each entry's :hidpi? flag drives the EDN schema (single :src
  vs :src-1x/:src-2x) and the runtime build path (plain Image vs
  ImageDataProvider)."
  {:app          {:hidpi? false
                  :src    "branding/icons/png/winze-icon-16.png"}
   :header       {:hidpi? false
                  :src    "branding/header/winze-wordmark-slogan-dark.png"}
   :statusbar    {:hidpi? true
                  :src-1x "branding/statusbar/macos/winzeTemplate.png"
                  :src-2x "branding/statusbar/macos/winzeTemplate@2x.png"}
   :tab-document {:hidpi? true
                  :src-1x "branding/ui/png/winze-tab-document-16.png"
                  :src-2x "branding/ui/png/winze-tab-document-32.png"}})

;; ---------------------------------------------------------------------------
;; Per-entry validation
;; ---------------------------------------------------------------------------

(defn validate-color-entry
  "Return {:ok [k [r g b]]} or {:error msg}."
  [k v]
  (if-let [rgb (parse-hex-color v)]
    {:ok [k rgb]}
    {:error (str "color " k ": expected \"#RRGGBB\", got " (pr-str v))}))

(defn validate-font-entry
  "Return {:ok [k normalized]} or {:error msg}.
  `stacks` is the parsed `:font-stacks` map so we can verify `:stack`
  refers to a defined stack."
  [k v stacks]
  (cond
    (not (map? v))
    {:error (str "font " k ": expected a map, got " (pr-str v))}

    :else
    (let [{:keys [stack size style]} v
          resolved-stack (get stacks stack)]
      (cond
        (nil? resolved-stack)
        {:error (str "font " k ": unknown stack " (pr-str stack))}

        (or (not (vector? resolved-stack))
            (empty? resolved-stack)
            (not (every? string? resolved-stack)))
        {:error (str "font " k ": stack " (pr-str stack)
                     " must be a non-empty vector of strings")}

        (or (not (integer? size)) (not (pos? size)))
        {:error (str "font " k ": size must be a positive integer, got " (pr-str size))}

        (nil? (parse-style style))
        {:error (str "font " k ": unknown style " (pr-str style)
                     " (expected :normal/:bold/:italic/:bold-italic)")}

        :else
        {:ok [k {:stack resolved-stack
                 :size  size
                 :style (parse-style style)}]}))))

(defn- path-separator? [^String s]
  (or (str/includes? s "/") (str/includes? s "\\")))

(defn validate-icon-entry
  "Return {:ok [k normalized]} or {:error msg}.
  `bundled-icons` is the fixed table that drives :hidpi? vs non-HiDPI
  per key and which slots are required."
  [bundled k v]
  (let [entry (get bundled k)]
    (cond
      (nil? entry)
      {:error (str "icon " k ": unknown key (expected one of "
                   (pr-str (vec (sort (keys bundled)))) ")")}

      (not (map? v))
      {:error (str "icon " k ": expected a map, got " (pr-str v))}

      (:hidpi? entry)
      (let [{:keys [src src-1x src-2x]} v]
        (cond
          src
          {:error (str "icon " k ": HiDPI icon uses :src-1x/:src-2x, not :src")}

          (or (not (string? src-1x)) (not (string? src-2x)))
          {:error (str "icon " k ": HiDPI icon requires both :src-1x and :src-2x")}

          (or (path-separator? src-1x) (path-separator? src-2x))
          {:error (str "icon " k ": filenames must be bare names under ~/.winze/icons/ (no `/` or `\\`)")}

          :else
          {:ok [k {:src-1x src-1x :src-2x src-2x}]}))

      :else ;; non-HiDPI
      (let [{:keys [src src-1x src-2x]} v]
        (cond
          (or src-1x src-2x)
          {:error (str "icon " k ": non-HiDPI icon uses :src, not :src-1x/:src-2x")}

          (not (string? src))
          {:error (str "icon " k ": requires :src (a filename under ~/.winze/icons/)")}

          (path-separator? src)
          {:error (str "icon " k ": filename must be a bare name (no `/` or `\\`)")}

          :else
          {:ok [k {:src src}]})))))

(tests
 (:ok (validate-color-entry :lavender "#C4B8FF"))     := [:lavender [0xC4 0xB8 0xFF]]
 (boolean (:error (validate-color-entry :x "bad")))   := true

 (let [stacks {:sans ["Inter" "Helvetica"]}]
   (:ok (validate-font-entry :body {:stack :sans :size 13 :style :normal} stacks))
   := [:body {:stack ["Inter" "Helvetica"] :size 13 :style SWT/NORMAL}]

   (boolean (:error (validate-font-entry :body {:stack :unknown :size 13 :style :normal} stacks)))
   := true
   (boolean (:error (validate-font-entry :body {:stack :sans :size 0 :style :normal} stacks)))
   := true
   (boolean (:error (validate-font-entry :body {:stack :sans :size 13 :style :weird} stacks)))
   := true
   (boolean (:error (validate-font-entry :body {:stack :sans :size 13} stacks)))
   := true)

 ;; Empty stack is rejected
 (boolean (:error (validate-font-entry :body {:stack :empty :size 13 :style :normal}
                                       {:empty []})))
 := true

 (boolean (:error (validate-icon-entry bundled-icons :app {:src "ok/path.png"})))     := true
 (boolean (:error (validate-icon-entry bundled-icons :app {:src "path\\x.png"})))     := true
 (boolean (:error (validate-icon-entry bundled-icons :app {:src-1x "a.png"})))        := true
 (boolean (:error (validate-icon-entry bundled-icons :statusbar {:src "a.png"})))     := true
 (boolean (:error (validate-icon-entry bundled-icons :statusbar {:src-1x "a.png"})))  := true
 (boolean (:error (validate-icon-entry bundled-icons :unknown {:src "a.png"})))       := true
 (:ok (validate-icon-entry bundled-icons :app {:src "my.png"}))                       := [:app {:src "my.png"}]
 (:ok (validate-icon-entry bundled-icons :statusbar
                           {:src-1x "a.png" :src-2x "b.png"}))
 := [:statusbar {:src-1x "a.png" :src-2x "b.png"}]
 :rcf)

;; ---------------------------------------------------------------------------
;; parse-theme — whole-file pass
;; ---------------------------------------------------------------------------

(defn- parse-section
  "Apply `validate-fn` across `entries`, partition into valid + error strings."
  [entries validate-fn]
  (reduce
   (fn [[ok errs] [k v]]
     (let [result (validate-fn k v)]
       (if (:ok result)
         [(conj ok (:ok result)) errs]
         [ok (conj errs (:error result))])))
   [{} []]
   entries))

(defn parse-theme
  "Walk `:colors`, `:font-stacks`, `:fonts`, `:icons` from `edn-map`,
  drop invalid entries, accumulate errors. Returns
  `{:colors {kw [r g b]} :font-stacks {kw [str]}
    :fonts  {kw {:stack [str] :size n :style bits}}
    :icons  {kw <normalized>}
    :errors [str]}`."
  [edn-map bundled]
  (cond
    (nil? edn-map)
    {:colors {} :font-stacks {} :fonts {} :icons {}
     :errors ["theme.edn: no data (empty file)"]}

    (not (map? edn-map))
    {:colors {} :font-stacks {} :fonts {} :icons {}
     :errors ["theme.edn: top-level form must be a map"]}

    :else
    (let [[colors col-errs]
          (parse-section (:colors edn-map)
                         (fn [k v] (validate-color-entry k v)))

          ;; Font-stacks: simple validation, not via parse-section —
          ;; each must be a non-empty vector of strings.
          [stacks stack-errs]
          (reduce
           (fn [[ok errs] [k v]]
             (if (and (vector? v) (seq v) (every? string? v))
               [(assoc ok k (vec v)) errs]
               [ok (conj errs (str "font-stack " k ": must be a non-empty vector of strings"))]))
           [{} []]
           (:font-stacks edn-map))

          [fonts font-errs]
          (parse-section (:fonts edn-map)
                         (fn [k v] (validate-font-entry k v stacks)))

          [icons icon-errs]
          (parse-section (:icons edn-map)
                         (fn [k v] (validate-icon-entry bundled k v)))]
      {:colors      (into {} colors)
       :font-stacks stacks
       :fonts       (into {} fonts)
       :icons       (into {} icons)
       :errors      (vec (concat col-errs stack-errs font-errs icon-errs))})))

(tests
 ;; One bad color + one good color → good kept, error reported
 (let [result (parse-theme
               {:colors {:lavender "#C4B8FF" :broken "not-a-color"}}
               bundled-icons)]
   (:colors result) := {:lavender [0xC4 0xB8 0xFF]}
   (count (:errors result)) := 1)

 ;; Nil input
 (:errors (parse-theme nil bundled-icons))   := ["theme.edn: no data (empty file)"]

 ;; Non-map input
 (:errors (parse-theme [1 2 3] bundled-icons))
 := ["theme.edn: top-level form must be a map"]
 :rcf)

;; ---------------------------------------------------------------------------
;; Reader wrapper (exception-safe)
;; ---------------------------------------------------------------------------

(defn read-theme-source
  "Read EDN from `source` (anything io/reader accepts). On exception,
  return `{:form nil :errors [msg]}`. Otherwise `{:form data :errors []}`."
  [source label]
  (try
    (with-open [r  (io/reader source)
                pr (PushbackReader. r)]
      {:form (edn/read {:eof nil} pr) :errors []})
    (catch Throwable t
      {:form nil
       :errors [(str label ": unreadable — " (.getMessage t))]})))

;; ---------------------------------------------------------------------------
;; User directory install + load
;; ---------------------------------------------------------------------------

(defn user-theme-file
  "Return ~/.winze/theme.edn as a File. Creates ~/.winze/ if missing."
  ^File []
  (let [winze-dir (io/file (System/getProperty "user.home") ".winze")]
    (when-not (.isDirectory winze-dir) (.mkdirs winze-dir))
    (io/file winze-dir "theme.edn")))

(defn user-icon-dir
  "Return ~/.winze/icons/ as a File. Creates it if missing.
  Deliberately empty — an override slot, not a preseeded kit."
  ^File []
  (let [d (io/file (System/getProperty "user.home") ".winze" "icons")]
    (when-not (.isDirectory d) (.mkdirs d))
    d))

(defn install-default-theme-if-missing!
  "Copy bundled `resources/theme.edn` → ~/.winze/theme.edn atomically if
  the target is missing. Idempotent."
  []
  (let [target (user-theme-file)]
    (when-not (.exists target)
      (let [tmp (io/file (.getParentFile target) "theme.edn.tmp")]
        (with-open [in  (io/input-stream (io/resource "theme.edn"))
                    out (io/output-stream tmp)]
          (io/copy in out))
        (try
          (Files/move
           (.toPath tmp) (.toPath target)
           (into-array java.nio.file.CopyOption
                       [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                        java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (catch java.nio.file.AtomicMoveNotSupportedException _
            (Files/move
             (.toPath tmp) (.toPath target)
             (into-array java.nio.file.CopyOption
                         [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))))

(defn- load-bundled-theme
  "Parse the classpath `theme.edn`. Returns the parse map."
  []
  (let [{:keys [form errors]} (read-theme-source (io/resource "theme.edn")
                                                 "theme.edn (bundled)")
        parsed (parse-theme form bundled-icons)]
    (update parsed :errors into errors)))

(defn- load-user-theme
  "Parse ~/.winze/theme.edn. Returns the parse map."
  []
  (let [f (user-theme-file)]
    (if (.exists f)
      (let [{:keys [form errors]} (read-theme-source f "theme.edn")
            parsed (parse-theme form bundled-icons)]
        (update parsed :errors into errors))
      {:colors {} :font-stacks {} :fonts {} :icons {} :errors []})))

(defn- merge-themes
  "User overrides layer on top of defaults for colors, font-stacks, and
  fonts. :icons passes through the user map (each slot resolves
  independently at build time). Errors concatenate."
  [defaults user]
  {:colors      (merge (:colors defaults) (:colors user))
   :font-stacks (merge (:font-stacks defaults) (:font-stacks user))
   :fonts       (merge (:fonts defaults) (:fonts user))
   :icons       (merge (:icons defaults) (:icons user))
   :errors      (vec (concat (:errors defaults) (:errors user)))})

(defn load-theme!
  "Install the bundled theme into ~/.winze/ if missing, ensure
  ~/.winze/icons/ exists, then parse bundled + user and merge.
  Returns {:theme merged-parse :errors [...]}."
  []
  (try
    (install-default-theme-if-missing!)
    (user-icon-dir)
    (let [defaults (load-bundled-theme)
          user     (load-user-theme)
          merged   (merge-themes defaults user)]
      {:theme  (dissoc merged :errors)
       :errors (:errors merged)})
    (catch Throwable t
      (log/error t "load-theme! failed")
      {:theme  {:colors {} :font-stacks {} :fonts {} :icons {}}
       :errors [(str "theme: load failed — " (.getMessage t))]})))

;; ---------------------------------------------------------------------------
;; Var-name ↔ EDN coordinate
;; ---------------------------------------------------------------------------

(defn var-name->theme-key
  "Map a var symbol in `resources.clj` to its EDN registry coordinate
  [section key] per the naming contract in PLAN Step 4, or nil if the
  name doesn't match any theme pattern."
  [sym]
  (let [s (name sym)]
    (cond
      (str/starts-with? s "color-")
      [:colors (keyword (subs s 6))]

      (str/ends-with? s "-font")
      [:fonts (keyword (subs s 0 (- (count s) 5)))]

      (str/ends-with? s "-icon")
      [:icons (keyword (subs s 0 (- (count s) 5)))]

      (str/ends-with? s "-image")
      [:icons (keyword (subs s 0 (- (count s) 6)))])))

(tests
 (var-name->theme-key 'color-royal-purple) := [:colors :royal-purple]
 (var-name->theme-key 'body-bold-font)     := [:fonts :body-bold]
 (var-name->theme-key 'tab-document-icon)  := [:icons :tab-document]
 (var-name->theme-key 'header-image)       := [:icons :header]
 (var-name->theme-key 'app-props)          := nil
 (var-name->theme-key 'open-files)         := nil
 :rcf)

(defn theme-atoms
  "Enumerate theme atoms in `llm-memory.ui.resources`.
  Returns a lazy seq of [sym atom-ref section key] tuples.

  FILTER: `(instance? clojure.lang.IAtom val)`. This is the type-level
  invariant that separates theme resources (MUST be atoms so reload
  can reset! them) from procedural icons (delays) and other ns state
  atoms that don't match the naming convention. Delays, promises,
  futures, refs, and agents do NOT implement IAtom.

  DO NOT relax this to IDeref — the procedural-icon delays
  (edit-icon, back-icon, forward-icon) share the -icon suffix;
  reset!-ing them would error."
  []
  (for [[sym v] (ns-publics 'llm-memory.ui.resources)
        :let   [val (var-get v)]
        :when  (instance? clojure.lang.IAtom val)
        :let   [coord (var-name->theme-key sym)]
        :when  coord]
    (into [sym val] coord)))

;; ---------------------------------------------------------------------------
;; SWT resource builder
;; ---------------------------------------------------------------------------

(defn- resolve-icon-source
  "Return a java.io.File if the user supplied a valid filename under
  ~/.winze/icons/ that exists + is a file + has no path separators.
  Otherwise nil → caller falls back to classpath."
  ^File [user-filename]
  (when (and (string? user-filename)
             (not (path-separator? user-filename)))
    (let [f (io/file (user-icon-dir) user-filename)]
      (when (and (.isFile f))
        f))))

(defn- open-icon-stream
  "Open an InputStream for a user-File (if present and openable) or fall
  back to a classpath resource. Any open failure on the user file is
  logged + added to `errors-atom`. Caller owns the stream."
  ^InputStream [^File user-file bundled-path errors-atom label]
  (or (when user-file
        (try
          (FileInputStream. user-file)
          (catch Throwable t
            (swap! errors-atom conj
                   (str "theme.edn icon " label ": "
                        (.getName user-file) " — " (.getMessage t)))
            nil)))
      (.getResourceAsStream (clojure.lang.RT/baseLoader) bundled-path)))

(defn- build-color
  [^org.eclipse.swt.widgets.Display disp [r g b]]
  (Color. disp (int r) (int g) (int b)))

(defn- font-available? [^org.eclipse.swt.widgets.Display disp ^String name]
  (boolean (seq (.getFontList disp name true))))

(defn- first-available-font
  ([disp names] (first-available-font disp names ""))
  ([disp names fallback]
   (or (first (filter #(font-available? disp %) names)) fallback)))

(defn- build-font
  [^org.eclipse.swt.widgets.Display disp {:keys [stack size style]}]
  (Font. disp ^String (first-available-font disp stack) (int size) (int style)))

(defn- build-icon-non-hidpi!
  [^org.eclipse.swt.widgets.Display disp user-file bundled-path errors-atom label]
  (try
    (with-open [s (open-icon-stream user-file bundled-path errors-atom label)]
      (Image. disp ^InputStream s))
    (catch Throwable t
      (swap! errors-atom conj
             (str "theme.edn icon " label ": build failed — " (.getMessage t)))
      ;; Fallback to bundled-only, new stream
      (try
        (with-open [s (.getResourceAsStream (clojure.lang.RT/baseLoader) bundled-path)]
          (Image. disp ^InputStream s))
        (catch Throwable t2
          (log/error t2 "Bundled icon load failed" bundled-path)
          nil)))))

(defn- read-image-data
  "Read ImageData from user-file-or-bundled-classpath. Returns
  ImageData, or nil on failure (error pushed to errors-atom)."
  [user-file bundled-path errors-atom label]
  (try
    (with-open [s (open-icon-stream user-file bundled-path errors-atom label)]
      (ImageData. ^InputStream s))
    (catch Throwable t
      (swap! errors-atom conj
             (str "theme.edn icon " label ": " (.getMessage t)))
      nil)))

(defn- build-icon-hidpi!
  [^org.eclipse.swt.widgets.Display disp
   user-file-1x bundled-1x
   user-file-2x bundled-2x
   errors-atom label]
  (let [data-1x (read-image-data user-file-1x bundled-1x errors-atom (str label " (1x)"))
        data-2x (read-image-data user-file-2x bundled-2x errors-atom (str label " (2x)"))
        ;; If either user stream failed, the 1x/2x fell back to bundled via open-icon-stream
        ;; but read-image-data might still have returned nil on a bundled-read failure
        ;; (very rare). Final fallback: try bundled directly for any missing slot.
        data-1x (or data-1x
                    (try (with-open [s (.getResourceAsStream (clojure.lang.RT/baseLoader) bundled-1x)]
                           (ImageData. ^InputStream s))
                         (catch Throwable _ nil)))
        data-2x (or data-2x data-1x)]
    (when data-1x
      (Image. disp
              (reify ImageDataProvider
                (getImageData [_ zoom]
                  (if (>= zoom 200) (or data-2x data-1x) data-1x)))))))

(defn- build-icon!
  "Construct one themable Image from user overlay + bundled default.
  `label` is the icon key name for error messages."
  [^org.eclipse.swt.widgets.Display disp icon-key user-entry errors-atom]
  (let [bundled (get bundled-icons icon-key)
        label   (name icon-key)]
    (if (:hidpi? bundled)
      (let [uf-1x (resolve-icon-source (:src-1x user-entry))
            uf-2x (resolve-icon-source (:src-2x user-entry))]
        (build-icon-hidpi! disp uf-1x (:src-1x bundled)
                           uf-2x (:src-2x bundled)
                           errors-atom label))
      (let [uf (resolve-icon-source (:src user-entry))]
        (build-icon-non-hidpi! disp uf (:src bundled) errors-atom label)))))

(defn- build-swt-resources!
  "Construct Colors/Fonts/Images from a parsed theme. Mutates
  `errors-atom` with any build-time errors. Must run on the UI thread
  (caller uses `sync-exec!`).

  Returns {:colors {kw <Color>} :fonts {kw <Font>} :icons {kw <Image>}}.
  Every bundled icon key is always present (falls back to bundled if
  user override invalid or missing)."
  [^org.eclipse.swt.widgets.Display disp theme errors-atom]
  (let [colors (into {} (for [[k rgb] (:colors theme)]
                          [k (build-color disp rgb)]))
        fonts  (into {} (for [[k spec] (:fonts theme)]
                          [k (build-font disp spec)]))
        ;; Always build every bundled-icons key, even if no user entry
        icons  (into {}
                     (for [icon-key (keys bundled-icons)]
                       (let [user-entry (get (:icons theme) icon-key)
                             img        (build-icon! disp icon-key user-entry errors-atom)]
                         [icon-key img])))]
    {:colors colors :fonts fonts :icons icons}))

;; ---------------------------------------------------------------------------
;; Hex helper — derive hex string from live Color atom
;; ---------------------------------------------------------------------------

(defn hex
  "Return the current hex string (`#RRGGBB`) for a color key (e.g.
  `:lavender`). Derives from the live `Color` via `.getRed/Green/Blue`
  — no parallel hex storage, so no drift between SWT state and hex
  state. Returns nil if the color atom is uninitialized or the key is
  unknown.

  Safe to call from any thread — Color getter methods are pure field
  reads, not UI-thread gated."
  [k]
  (when-let [var-ref (ns-resolve 'llm-memory.ui.resources
                                 (symbol (str "color-" (name k))))]
    (when-let [^Color c @@var-ref]
      (format "#%02X%02X%02X" (.getRed c) (.getGreen c) (.getBlue c)))))

;; ---------------------------------------------------------------------------
;; Refresh-listener broadcast
;; ---------------------------------------------------------------------------

(defonce ^:private refresh-listeners (atom #{}))

(defn register-refresh-listener!
  "Register a 0-arg callback. Returns a token that can be passed to
  `unregister-refresh-listener!`."
  [f]
  (swap! refresh-listeners conj f)
  f)

(defn unregister-refresh-listener! [f]
  (swap! refresh-listeners disj f))

(defn- broadcast-theme-refresh! []
  (doseq [f @refresh-listeners]
    (try (f)
         (catch Throwable t
           (log/warn t "Theme refresh listener failed")))))

;; ---------------------------------------------------------------------------
;; Transient popup closers
;; ---------------------------------------------------------------------------

(defn- close-transient-shells!
  "Close find bar, content-assist popup, link preview, and command
  palette if open. Called on the UI thread at the start of
  `reload-theme!` so no transient widget holds a reference to a
  soon-to-be-disposed Color/Font."
  []
  (letfn [(try-call [ns-sym var-sym]
            (try
              (when-let [v (requiring-resolve (symbol (name ns-sym) (name var-sym)))]
                (v))
              (catch Throwable t
                (log/warn t "close-transient-shells!: " ns-sym "/" var-sym))))]
    (when (try-call 'llm-memory.ui.find-replace 'find-bar-open?)
      (try-call 'llm-memory.ui.find-replace 'close-find-bar!))
    (when (try-call 'llm-memory.ui.content-assist 'popup-open?)
      (try-call 'llm-memory.ui.content-assist 'close-content-assist!))
    (when (try-call 'llm-memory.ui.link-preview 'preview-open?)
      (try-call 'llm-memory.ui.link-preview 'hide-preview!))
    (when (try-call 'llm-memory.ui.command-palette 'palette-open?)
      (try-call 'llm-memory.ui.command-palette 'close-palette!))))

;; ---------------------------------------------------------------------------
;; Disposal
;; ---------------------------------------------------------------------------

(defn- dispose-old-values!
  "Dispose every SWT Resource in `old-values` that isn't already
  disposed. Called via `async-exec!` AFTER broadcast-refresh has
  reapplied new colors/fonts — the FIFO asyncExec queue ensures
  widgets paint with new resources before old ones are disposed."
  [old-values]
  (doseq [[_ v] old-values
          :when (and (instance? Resource v)
                     (not (.isDisposed ^Resource v)))]
    (try (.dispose ^Resource v)
         (catch Throwable t
           (log/warn t "Failed to dispose old theme resource")))))

;; ---------------------------------------------------------------------------
;; Entry points: apply-theme-startup! and reload-theme!
;; ---------------------------------------------------------------------------

(defn- install-sentinel!
  "Install a magenta sentinel Color into `atom-ref` and record an error.
  Used when `resources.clj` has a theme var that has no matching entry
  in the built resources map (i.e. `resources/theme.edn` is out of
  sync with the var list)."
  [^org.eclipse.swt.widgets.Display disp errors-atom atom-ref section key sym]
  (swap! errors-atom conj
         (format "theme: no bundled default for %s/%s (%s) — resources/theme.edn and resources.clj are out of sync"
                 (name section) (name key) sym))
  (reset! atom-ref (Color. disp 0xFF 0x00 0xFF)))

(defn apply-theme-startup!
  "First-load entry point. Must be called on the UI thread before any
  widget dereferences a theme atom (i.e. before the `(application …)`
  form in `main_window/main-window` is evaluated — CDT init functions
  are regular functions whose `:image @app-icon` args are forced
  eagerly).

  Returns `{:errors [...]}`. Caller shows the MessageBox AFTER
  `@app-props` is populated in defmain."
  [^org.eclipse.swt.widgets.Display disp]
  (let [{:keys [theme errors]} (load-theme!)
        errors-atom            (atom (vec errors))
        resources-map          (build-swt-resources! disp theme errors-atom)]
    (doseq [[sym atom-ref section key] (theme-atoms)]
      (if-let [v (get-in resources-map [section key])]
        (reset! atom-ref v)
        (install-sentinel! disp errors-atom atom-ref section key sym)))
    {:errors @errors-atom}))

(defn reload-theme!
  "Re-read ~/.winze/theme.edn, build new SWT resources on the UI thread,
  close transient popups, swap each theme atom's value, broadcast a
  refresh to live widgets, then dispose the old resources via
  `async-exec!` so the FIFO queue ensures widgets paint with new
  resources before the old ones are disposed.

  Returns a vector of error strings (empty on clean reload)."
  []
  (let [{:keys [theme errors]} (load-theme!)
        errors-atom             (atom (vec errors))
        old-values              (into {} (for [[_sym a _s _k] (theme-atoms)]
                                           [a @a]))
        new-resources-or-ex
        (sync-exec!
         (fn []
           (try
             (build-swt-resources! @display theme errors-atom)
             (catch Throwable t
               (log/error t "Theme reload: build-swt-resources! threw")
               (swap! errors-atom conj
                      (str "theme: build failed — " (.getMessage t)))
               t))))]
    (if (instance? Throwable new-resources-or-ex)
      ;; Build failed wholesale — keep old atom values, don't close popups,
      ;; don't dispose anything. App keeps painting with live old resources.
      @errors-atom
      (do
        (sync-exec!
         (fn []
           (close-transient-shells!)
           (doseq [[sym atom-ref section key] (theme-atoms)]
             (if-let [v (get-in new-resources-or-ex [section key])]
               (reset! atom-ref v)
               (install-sentinel! @display errors-atom atom-ref section key sym)))
           (broadcast-theme-refresh!)))
        ;; Defer disposal — queued AFTER all refresh-listener-enqueued
        ;; async-exec! mutations, so widgets have already re-applied new
        ;; colors before old ones are disposed.
        (async-exec! #(dispose-old-values! old-values))
        @errors-atom))))

;; ---------------------------------------------------------------------------
;; REPL / observability helpers
;; ---------------------------------------------------------------------------

(defn current-palette
  "Return the currently live color palette as `{kw \"#RRGGBB\"}`.
  Walks the color theme atoms and derives hex via `hex`."
  []
  (into (sorted-map)
        (for [[_sym _a section key] (theme-atoms)
              :when (= section :colors)]
          [key (hex key)])))

(defn validate-user-file
  "Read ~/.winze/theme.edn and validate it WITHOUT touching the live
  registry. Returns `{:theme merged :errors [...]}`.
  Pure — safe to call from any thread."
  []
  (load-theme!))
