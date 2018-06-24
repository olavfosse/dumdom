(ns dumdom.core-test
  (:require [cljs.test :as t :refer-macros [testing is]]
            [clojure.string :as str]
            [devcards.core :refer-macros [deftest]]
            [dumdom.core :as sut]
            [dumdom.dom :as d]
            [dumdom.test-helper :refer [render render-str]]))

(deftest component-render
  (testing "Renders component"
    (let [comp (sut/component (fn [data] (d/div {:className "lol"} data)))]
      (is (= "<div class=\"lol\">1</div>" (render-str (comp 1))))))

  (testing "Does not re-render when immutable value hasn't changed"
    (let [mutable-state (atom [1 2 3])
          comp (sut/component (fn [data]
                                (let [v (first @mutable-state)]
                                  (swap! mutable-state rest)
                                  (d/div {} v))))]
      (is (= "<div>1</div>" (render-str (comp {:number 1}))))
      (is (= "<div>1</div>" (render-str (comp {:number 1}))))
      (is (= "<div>2</div>" (render-str (comp {:number 2}))))))

  (testing "Re-renders instance of component at different position in tree"
    (let [mutable-state (atom [1 2 3])
          comp (sut/component (fn [data]
                                (let [v (first @mutable-state)]
                                  (swap! mutable-state rest)
                                  (d/div {} v))))]
      (is (= "<div>1</div>" (render-str (comp {:number 1}) [] 0)))
      (is (= "<div>2</div>" (render-str (comp {:number 1}) [] 1)))
      (is (= "<div>1</div>" (render-str (comp {:number 1}) [] 0)))
      (is (= "<div>2</div>" (render-str (comp {:number 1}) [] 1)))
      (is (= "<div>3</div>" (render-str (comp {:number 2}) [] 1)))))

  (testing "Ignores provided position when component has a keyfn"
    (let [mutable-state (atom [1 2 3])
          comp (sut/component (fn [data]
                                (let [v (first @mutable-state)]
                                  (swap! mutable-state rest)
                                  (d/div {} v)))
                              {:keyfn :id})]
      (is (= "<div>1</div>" (render-str (comp {:id "c1" :number 1}) [] 0)))
      (is (= "<div>1</div>" (render-str (comp {:id "c1" :number 1}) [] 1)))
      (is (= "<div>2</div>" (render-str (comp {:id "c2" :number 1}) [] 0)))
      (is (= "<div>3</div>" (render-str (comp {:number 1}) [] 0)))
      (is (= "<div>2</div>" (render-str (comp {:id "c2" :number 1}) [] 1)))))

  (testing "Sets key on vdom node"
    (let [comp (sut/component (fn [data]
                                (d/div {} (:val data)))
                              {:keyfn :id})]
      (is (= "c1" (.-key (render (comp {:id "c1" :val 1})))))))

  (testing "keyfn overrides vdom node key"
    (let [comp (sut/component (fn [data]
                                (d/div {:key "key"} (:val data)))
                              {:keyfn :id})]
      (is (= "c1" (.-key (render (comp {:id "c1" :val 1})))))))

  (testing "Passes constant args to component, but does not re-render when they change"
    (let [calls (atom [])
          comp (sut/component (fn [& args]
                                (swap! calls conj args)
                                (d/div {:key "key"} "OK")))]
      (render (comp {:id "v1"} 1 2 3))
      (render (comp {:id "v1"} 2 3 4))
      (render (comp {:id "v2"} 3 4 5))
      (render (comp {:id "v2"} 4 5 6))
      (render (comp {:id "v3"} 5 6 7))
      (is (= [[{:id "v1"} 1 2 3]
              [{:id "v2"} 3 4 5]
              [{:id "v3"} 5 6 7]] @calls))))

  (testing "Renders component as child of DOM element"
    (let [comp (sut/component (fn [data]
                                (d/p {} "From component")))]
      (is (= "<div class=\"wrapper\"><p>From component</p></div>"
             (render-str (d/div {:className "wrapper"}
                           (comp {:id "v1"} 1 2 3))))))))

(deftest render-test
  (testing "Renders vnode to DOM"
    (let [el (js/document.createElement "div")]
      (sut/render (d/div {} "Hello") el)
      (is (= "<div>Hello</div>" (.-innerHTML el))))))

(deftest on-mount-test
  (testing "Calls on-mount when component first mounts"
    (let [el (js/document.createElement "div")
          on-mount (atom nil)
          component (sut/component
                     (fn [_] (d/div {} "LOL"))
                     {:on-mount (fn [node & args]
                                  (reset! on-mount (apply vector node args)))})]
      (sut/render (component {:a 42} {:static "Prop"} {:another "Static"}) el)
      (is (= [(.-firstChild el) {:a 42} {:static "Prop"} {:another "Static"}]
             @on-mount))))

  (testing "Calls both on-mount and ref callback"
    (let [el (js/document.createElement "div")
          on-mount (atom nil)
          ref (atom nil)
          component (sut/component
                     (fn [data] (d/div {:ref #(reset! ref data)} (:text data)))
                     {:on-mount #(reset! on-mount %2)})]
      (sut/render (component {:text "Look ma!"}) el)
      (is (= {:text "Look ma!"} @on-mount))
      (is (= {:text "Look ma!"} @ref))))

  (testing "Does not call on-mount on update"
    (let [el (js/document.createElement "div")
          on-mount (atom [])
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:on-mount (fn [node data]
                                  (swap! on-mount conj data))})]
      (sut/render (component {:text "LOL"}) el)
      (sut/render (component {:text "Hello"}) el)
      (is (= 1 (count @on-mount))))))

(deftest on-update-test
  (testing "Does not call on-update when component first mounts"
    (let [el (js/document.createElement "div")
          on-update (atom nil)
          component (sut/component
                     (fn [_] (d/div {} "LOL"))
                     {:on-update (fn [node & args]
                                   (reset! on-update (apply vector node args)))})]
      (sut/render (component {:a 42} {:static "Prop"} {:another "Static"}) el)
      (is (nil? @on-update))))

  (testing "Calls on-update on each update"
    (let [el (js/document.createElement "div")
          on-update (atom [])
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:on-update (fn [node data]
                                   (swap! on-update conj data))})]
      (sut/render (component {:text "LOL"}) el)
      (sut/render (component {:text "Hello"}) el)
      (sut/render (component {:text "Aight"}) el)
      (is (= [{:text "Hello"} {:text "Aight"}] @on-update)))))

(deftest on-render-test
  (testing "Calls on-render when component first mounts"
    (let [el (js/document.createElement "div")
          on-render (atom nil)
          component (sut/component
                     (fn [_] (d/div {} "LOL"))
                     {:on-render (fn [node & args]
                                  (reset! on-render (apply vector node args)))})]
      (sut/render (component {:a 42} {:static "Prop"} {:another "Static"}) el)
      (is (= [(.-firstChild el) {:a 42} {:static "Prop"} {:another "Static"}]
             @on-render))))

  (testing "Calls on-render and on-mount when component first mounts"
    (let [el (js/document.createElement "div")
          on-render (atom nil)
          on-mount (atom nil)
          component (sut/component
                     (fn [_] (d/div {} "LOL"))
                     {:on-render (fn [node & args]
                                   (reset! on-render (apply vector node args)))
                      :on-mount (fn [node & args]
                                   (reset! on-mount (apply vector node args)))})]
      (sut/render (component {:a 42} {:static "Prop"} {:another "Static"}) el)
      (is (= [(.-firstChild el) {:a 42} {:static "Prop"} {:another "Static"}]
             @on-render
             @on-mount))))

  (testing "Calls on-render on each update"
    (let [el (js/document.createElement "div")
          on-render (atom [])
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:on-render (fn [node data]
                                   (swap! on-render conj data))})]
      (sut/render (component {:text "LOL"}) el)
      (sut/render (component {:text "Hello"}) el)
      (sut/render (component {:text "Aight"}) el)
      (is (= [{:text "LOL"}
              {:text "Hello"}
              {:text "Aight"}] @on-render))))

  (testing "Calls on-render and on-update on each update"
    (let [el (js/document.createElement "div")
          on-render (atom [])
          on-update (atom [])
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:on-render (fn [node data]
                                   (swap! on-render conj data))
                      :on-update (fn [node data]
                                   (swap! on-update conj data))})]
      (sut/render (component {:text "LOL"}) el)
      (sut/render (component {:text "Hello"}) el)
      (sut/render (component {:text "Aight"}) el)
      (is (= [{:text "LOL"}
              {:text "Hello"}
              {:text "Aight"}] @on-render))
      (is (= [{:text "Hello"}
              {:text "Aight"}] @on-update)))))

(deftest on-unmount-test
  (testing "Does not call on-unmount when component first mounts"
    (let [el (js/document.createElement "div")
          on-unmount (atom nil)
          component (sut/component
                     (fn [_] (d/div {} "LOL"))
                     {:on-unmount #(reset! on-unmount %)})]
      (sut/render (component {:a 42}) el)
      (is (nil? @on-unmount))))

  (testing "Does not call on-unmount when updating component"
    (let [el (js/document.createElement "div")
          on-unmount (atom nil)
          component (sut/component
                     (fn [_] (d/div {} "LOL"))
                     {:on-unmount #(reset! on-unmount %)})]
      (sut/render (component {:a 42}) el)
      (sut/render (component {:a 13}) el)
      (is (nil? @on-unmount))))

  (testing "Calls on-unmount when removing component"
    (let [el (js/document.createElement "div")
          on-unmount (atom nil)
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:on-unmount (fn [node & args]
                                    (reset! on-unmount (apply vector node args)))})]
      (sut/render (component {:text "LOL"}) el)
      (let [rendered (.-firstChild el)]
        (sut/render (d/h1 {} "Gone!") el)
        (is (= [rendered {:text "LOL"}] @on-unmount))))))

(deftest will-enter-test
  (testing "Does not call will-enter when parent mounts"
    (let [el (js/document.createElement "div")
          will-enter (atom nil)
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:will-enter (fn [node & args]
                                    (reset! will-enter (apply vector node args)))})]
      (sut/render (d/div {} (component {:text "LOL"})) el)
      (is (nil? @will-enter))))

  (testing "Calls will-enter when mounting element inside existing parent"
    (let [el (js/document.createElement "div")
          will-enter (atom nil)
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:will-enter (fn [node callback & args]
                                    (reset! will-enter (apply vector node args)))})]
      (sut/render (d/div {}) el)
      (sut/render (d/div {} (component {:text "LOL"})) el)
      (is (= [(.. el -firstChild -firstChild) {:text "LOL"}] @will-enter))))

  (testing "Calls did-enter when the callback passed to will-enter is called"
    (let [el (js/document.createElement "div")
          will-enter (atom nil)
          did-enter (atom nil)
          component (sut/component
                     (fn [data] (d/div {} (:text data)))
                     {:will-enter (fn [node callback & args] (reset! will-enter callback))
                      :did-enter (fn [node & args]
                                   (reset! did-enter (apply vector node args)))})]
      (sut/render (d/div {}) el)
      (sut/render (d/div {} (component {:text "LOL"})) el)
      (is (nil? @did-enter))
      (@will-enter)
      (is (= [(.. el -firstChild -firstChild) {:text "LOL"}] @did-enter)))))
