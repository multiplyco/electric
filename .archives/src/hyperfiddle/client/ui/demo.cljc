(ns hyperfiddle.client.ui.demo
  (:require [hyperfiddle.client.ui :as ui :refer [tag text use-state component]]
            [hyperfiddle.client.ui.sugar :refer [html]]
            [missionary.core :as m]))

(def ColorPicker
  (component
   {:will-mount   (partial prn "will-mount")
    :will-unmount (partial prn "will-unmount")
    :render       (fn [>props & _children>]
                    (tag :div nil
                         (text (m/latest #(str "You picked:" (:value %)) >props))
                         (tag :input (m/latest #(assoc % :type :color) >props))))}))

(defn has-option? [this v]
  #?(:cljs
     (when-let [list  (.-list this)]
       (let [len (.. list -options -length)]
         (when (pos? len)
           (loop [x 0]
             (if (= v (-> (.. list -options) (aget x) (.-value)))
               true
               (if (< x (dec len))
                 (recur (inc x))
                 false))))))))

(def Autocomplete
  (component {:did-mount (fn [elem]
                           (js/console.log "did-mount" elem))
              :render (fn [>props & children>]
                        (let [id (str (gensym))]
                          (tag :<> nil
                           (tag :input (m/latest #(assoc % :html/list id) >props))
                           (apply tag :datalist (m/ap {:html/id id}) children>))))}))

(defn root [>props]
  (let [[>title set-title!] (use-state "Hyperfiddle UI")]
    (tag :div (m/latest (partial merge {:html/style {:border "1px gray solid", :margin "1rem", :padding "1rem"}})
                        >props)
         (tag :h1 nil (ui/text >title))
         (tag :form nil
              (Autocomplete (m/latest (partial assoc {:html/type        :text,
                                                      :html/placeholder "Autocomplete title",
                                                      :html/class       "hf-cm-input",
                                                      :html/on-input    (fn [e]
                                                                          (let [this (.. e -target)
                                                                                v    (.-value this)]
                                                                            (if (has-option? this v)
                                                                              (do (.setCustomValidity this "")
                                                                                  (set-title! (.. e -target -value)))
                                                                              (do (.setCustomValidity this "Please select a valid value")
                                                                                  (.. this (closest "form") (reportValidity))))))}
                                               :html/value)
                                      >title)
                            (tag :option (m/ap {:html/value "alice"}))
                            (tag :option (m/ap {:html/value "bob"}))
                            (tag :option (m/ap {:html/value "charlie"})))))))

(def !props (atom {}))
(def >props (m/watch !props))

(defn set-prop! [k v]
  (swap! !props assoc k v))

(defn remove-prop! [k]
  (swap! !props dissoc k))

(defn ^:export mount-root! []
  #?(:cljs
     ((m/reactor
       (m/stream! (ui/mount-component-at-node! "hf-ui-dev-root" (root >props))))
      js/console.log js/console.error)))

(defn ^:export unmount-root! [task]
  (task))

(defn ^:export stress!
  "Mount and unmount to trigger GC and track memory leaks"
  []
  #?(:cljs
     (let [app (mount-root!)]
       (js/setTimeout (fn []
                        (unmount-root! app)
                        (stress!))
                      500))))