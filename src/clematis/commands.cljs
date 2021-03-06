(ns clematis.commands
  (:require [clojure.core.async :as async :include-macros true]
            [clojure.string :as str]
            [repl-tooling.editor-integration.connection :as conn]
            [repl-tooling.editor-helpers :as helpers]
            [repl-tooling.editor-integration.renderer :as render]
            [repl-tooling.eval :as eval]
            [clojure.reader :as edn]
            [promesa.core :as p]))

(defonce nvim (atom nil))

(defn info [text]
  (. ^js @nvim outWrite (str text "\n")))

(defn new-window [^js nvim buffer enter opts]
  (. nvim
    (request "nvim_open_win" #js [buffer enter (clj->js opts)])))

(defn- open-console! []
  (.command ^js @nvim "vertical botright 50 new [clematis-console]")
  (p/let [_ (p/delay 100)
          buffer (. @nvim -buffer)]
    (doto ^js buffer
          (.setOption "modifiable" false)
          (.setOption "swapfile" false)
          (.setOption "buftype" "nofile"))
    buffer))

(defn- replace-buffer-text [^js buffer text]
  (.. (. buffer setOption "modifiable" true)
      (then #(.-length buffer))
      (then #(. buffer setLines (clj->js text)
               #js {:start 0 :end % :strictIndexing true}))
      (then #(. buffer setOption "modifiable" false))))

(defn- with-buffer [^js nvim ^js buffer where text]
  (let [lines (cond-> text (string? text) str/split-lines)]
    (.. buffer
        (setLines (clj->js lines)
                  #js {:start 0 :end 0 :strictIndexing true})
        (then #(do
                 (. buffer setOption "modifiable" false)
                 (. buffer setOption "ft" "clojure")))
        (then #(new-window nvim buffer false {:relative "cursor"
                                              :focusable true
                                              :anchor "NW"
                                              :width 50
                                              :height 10
                                              :row 0
                                              :col 10})))))

(defn open-window! [^js nvim where text]
  (.. nvim
      (createBuffer false true)
      (then #(with-buffer nvim % where text))))

(defn new-result! [^js nvim]
  (let [w (open-window! nvim nil "...loading...")
        buffer (. w then #(.-buffer %))]
    (.. js/Promise
        (all #js [buffer w])
        (then (fn [[buffer window]]
                (.setOption window "wrap" false))))
    (.. w
        (then #(aset nvim "window" %))
        (then #(do
                 (.command nvim "map <buffer> <Return> :ClematisExpandView<CR>")
                 (.command nvim "map <buffer> o :ClematisExpandView<CR>"))))
    w))

(def ^private original-state {:clj-eval nil
                              :clj-aux nil
                              :cljs-eval nil
                              :commands nil})
(defonce state (atom original-state))

(defn- on-disconnect! []
  (reset! state original-state)
  (info "Disconnected from REPL"))

(defn- get-vim-data []
  (when-let [nvim ^js @nvim]
    (let [buffer (. nvim -buffer)
          file-name (. buffer then #(.-name %))
          code (.. buffer
                   (then #(.-lines %))
                   (then #(str/join "\n" %)))
          row (.eval nvim "line('.')")
          col (.eval nvim "col('.')")]
      (.. js/Promise
          (all #js [code file-name row col])
          (then (fn [[code file-name row col]]
                  {:contents code
                   :filename file-name
                   :range [[(dec row) (dec col)] [(dec row) (dec col)]]}))))))

(defonce eval-state (atom {:window nil
                           :id nil}))

(defn- on-start-eval [{:keys [id]}]
  (when-let [existing-win ^js (:window @eval-state)]
    (.close existing-win))
  (let [window ^js (new-result! @nvim)]
    (. window then #(swap! eval-state assoc
                           :window %
                           :id id))))

(defn- replace-buffer [buffer-p string]
  (.. buffer-p (then #(replace-buffer-text % string))))

(defonce commands-in-buffer (atom {}))
(defn- render-result-into-buffer [result buffer-p]
  (let [[string specials] (-> result render/txt-for-result render/repr->lines)]
    (. buffer-p then (fn [buffer]
                       (swap! commands-in-buffer update-in [:buffers (.-id buffer)]
                              merge {:specials specials
                                     :result result})))
    (replace-buffer buffer-p string)))

(defn- on-end-eval [{:keys [result id] :as a}]
  (when (and (= id (:id @eval-state))
             (:window @eval-state))
    (let [win ^js (:window @eval-state)]
      (if (:literal result)
        (-> win .-buffer (replace-buffer (-> result :result edn/read-string str/split-lines)))
        (->> win .-buffer (render-result-into-buffer
                           (render/parse-result result (:clj-eval @state) nil)))))))


(defn- notify! [{:keys [type title message]}]
  (info (str (-> type name (str/upper-case)) ": " title
             (when message (str " - " message)))))

(defn- append-to-console [fragment]
  (swap! state update :output str fragment)
  (replace-buffer-text (:console @state)
                       (str/split-lines (:output @state))))

(defn connect! [host port]
  (when-not (:clj-eval @state)
    (p/let [res (conn/connect! host port
                               {:on-disconnect on-disconnect!
                                :notify notify!
                                :get-config (constantly {:project-paths "."
                                                         :eval-mode :prefer-clj})
                                :on-stdout #(append-to-console %)
                                :on-stderr #(append-to-console %)
                                :editor-data get-vim-data
                                :on-eval on-end-eval
                                :on-start-eval on-start-eval})
            _ (swap! state assoc
                     :clj-eval (:clj/repl @res)
                     :clj-aux (:clj/aux @res)
                     :commands (:editor/commands @res))
            console-buffer (open-console!)]
      (swap! state assoc :output "" :console console-buffer)
      :ok)))

(defn- get-cur-position []
  (let [lines (.. @nvim -buffer (then #(.getLines ^js %)))
        row (.eval @nvim "line('.')")
        col (.eval @nvim "col('.')")]
    (.. js/Promise
        (all #js [(. lines then js->clj) (. row then dec) (. col then dec)])
        (then js->clj))))

(defn- run-fun-and-expand [fun buffer-p result]
  (fun #(render-result-into-buffer result buffer-p)))

(defn expand-block [^js nvim]
  (let [pos (get-cur-position)
        cur-buffer (.-buffer nvim)]
    (.. js/Promise
        (all #js [pos cur-buffer])
        (then (fn [[[_ row col] buffer]]
                (let [{:keys [result specials]} (get-in @commands-in-buffer
                                                        [:buffers (.-id buffer)])]
                  (some-> (get specials [row col])
                          (run-fun-and-expand cur-buffer result))))))))

(defn evaluate-top-block []
  (let [cmd (-> @state :commands :evaluate-top-block :command)]
    (and cmd (cmd))))

(defn evaluate-block []
  (let [cmd (-> @state :commands :evaluate-block :command)]
    (and cmd (cmd))))

(defn disconnect! []
  (let [cmd (-> @state :commands :disconnect :command)]
    (and cmd (cmd))))
