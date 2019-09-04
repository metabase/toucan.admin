(ns toucan.admin.views
  (:require [compojure.core :as compojure]
            [toucan
             [db :as db]
             [models :as models]]
            [toucan.admin
             [common :as common]
             [page :as page]
             [table :as table]]))

;;; ------------------------------------------------------ Util ------------------------------------------------------

(defn view? [view]
  (common/isa? view :view))

(defn- dispatch-on-view-and-model-class [multifn-var view model & _]
  (let [view  (keyword view)
        klass (class model)]
    ;; TODO - this does not handle cases where model isa a subclass of some other class
    [view (if-not (get (methods (var-get multifn-var)) [view klass])
            klass
            :default)]))


;;; -------------------------------------------------- View Handler --------------------------------------------------

(defmulti handle-request
  {:arglists '([view model request])}
  (partial dispatch-on-view-and-model-class #'handle-request)
  :hierarchy #'common/hierarchy)

(defmethod handle-request :default
  [_ _ _]
  nil)

(def ^:private model->routes (atom {}))

(declare build-routes)

(def ^:private cached-routes (atom (delay (build-routes))))

(defn add-route! [method path view model]
  (let [handler (fn [request]
                  (handle-request view common/*model* request))
        route   (compojure/make-route method (#'compojure/prepare-route path) handler)]
    (swap! model->routes update model conj route))
  (reset! cached-routes (delay (build-routes))))

(defn- model-views-handler []
  (apply
   compojure/routes
   (for [[model handlers] (dissoc @model->routes :default)]
     (compojure/context (format "/%s" (name model)) [] (apply compojure/routes handlers)))))

(defn- resolve-model [model-name]
  (try
    (db/resolve-model (symbol model-name))
    (catch Throwable e
      (throw (ex-info "Model does not exist."
                      {:toucan.admin/response? true
                       :status                 404
                       :body                   "Model does not exist."
                       :headers                {"Content-Type" "text/plain"}})))))

(defn- default-views-handler []
  (let [handler* (apply compojure/routes (:default @model->routes))
        handler  (fn [model-name request]
                   (let [model (resolve-model model-name)]
                     (binding [common/*model* model]
                       (handler* request))))]
    (compojure/context "/:model" [model] (partial handler model))))

(defn- build-routes []
  (compojure/routes
   (model-views-handler)
   (default-views-handler)))

(defn handle-view-request [request]
  (try
    (binding [common/*request* request]
      (@@cached-routes request))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (when-not (:toucan.admin/response? data)
          (throw e))
        (dissoc data :toucan.admin/response?)))))


;;; ------------------------------------------------ Defining a View -------------------------------------------------

(defmacro define-view
  {:style/indent 1}
  ([view method route]
   `(define-view ~view ~method ~route :default))

  ([view method route model]
   `(do
      (common/derive ~(keyword view) :view)
      (add-route! ~(keyword method) ~route ~(keyword view) ~model))))

(defmulti actions
  {:arglists '([view model result])}
  (partial dispatch-on-view-and-model-class #'actions)
  :hierarchy #'common/hierarchy)

(defmethod actions :default [_ _ _] nil)

(defmacro define-actions
  {:style/indent :defn}
  ([view model actions]
   `(define-actions ~view ~model nil ~actions))

  ([view model [result-binding] actions]
   `(defmethod actions [(keyword ~view) (class ~model)]
      [~'&view ~'&model ~(or result-binding '_)]
      ~actions)))

(defmulti crumbs
  {:arglists '([view model result])}
  (partial dispatch-on-view-and-model-class #'crumbs)
  :hierarchy #'common/hierarchy)

(defmethod crumbs :default [_ _ _] nil)

(defmacro define-crumbs
  {:style/indent :defn}
  ([view model crumbs]
   `(define-crumbs ~view ~model nil ~crumbs))

  ([view model [result-binding] crumbs]
   `(defmethod crumbs [(keyword ~view) (class ~model)]
      [~'&view ~'&model ~(or result-binding '_)]
      ~crumbs)))

(defmulti table-style
  {:arglists '([view model request])}
  (partial dispatch-on-view-and-model-class #'table-style)
  :hierarchy #'common/hierarchy)

(defmethod table-style :default
  [_ _ _]
  :default)

(defmacro use-table-style [view model table-style]
  `(defmethod table-style [(keyword ~view) (class ~model)]
     [~'_ ~'_ ~'_]
     ~table-style))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 Default Views                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; --------------------------------------------------- :list view ---------------------------------------------------

(page/define-page-style :list)

(define-view :list
  :get "/")

(defmulti fetch-list
  {:arglists '([model offset limit request])}
  (fn [model _ _ _]
    (class model)))

(defmethod fetch-list :default
  [model offset limit _]
  {:pre [(models/model? model)]}
  (db/select model
    {:order-by [[:id :asc]]
     :limit    limit
     :offset   offset}))

(defmulti list-page-title
  {:arglists '([view model items])}
  (partial dispatch-on-view-and-model-class #'list-page-title)
  :hierarchy #'common/hierarchy)

(defmethod list-page-title [:list :default]
  [_ model _]
  (format "%s List" (name model)))

(defmethod handle-request [:list :default]
  [view model {:keys [params uri], :as request}]
  (let [page-num          (or (some-> (:page params) Integer/parseUnsignedInt) 1)
        offset            (* (dec page-num) table/*page-size*)
        items             (fetch-list model offset table/*page-size* (dissoc params :page))
        ;; TODO - this should handle requests with existing params as well
        has-other-params? (seq (dissoc params :page))
        has-more-items?   (= (count items) table/*page-size*)
        next-page-url     (when (and has-more-items? (not has-other-params?))
                            (str uri "?page=" (inc page-num)))
        title             (list-page-title view model items)]
    {:status  200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (page/render-page :list
                {:title                      title
                 :contents-template-filename "toucan/admin/templates/list-view.html.mustache"
                 :contents-data              {:title title
                                              :body  (table/table (table-style view model request)
                                                       items
                                                       :nextPage next-page-url)}
                 :actions                    (actions view model items)
                 :crumbs                     (crumbs view model items)})}))


;;; -------------------------------------------------- :detail view --------------------------------------------------

(page/define-page-style :detail)

(define-view :detail
  :get "/:id")

(defmulti fetch-one
  {:arglists '([model request])}
  (fn [model _]
    (class model)))

(defmethod fetch-one :default
  [model {:keys [id], :as params}]
  (let [params (cond-> params
                 (and id (re-matches #"^\d+$" id))
                 (update :id #(Long/parseUnsignedLong %)))]
    (db/select-one model
      (when (seq params)
        {:where (into [:and] (for [[k v] params]
                               [:= k v]))}))))

(defmulti detail-page-title
  {:arglists '([view model item])}
  (partial dispatch-on-view-and-model-class #'detail-page-title)
  :hierarchy #'common/hierarchy)

(defmethod detail-page-title [:detail :default]
  [_ model item]
  (format "%s %s" (name model) (:id item)))

(defmethod handle-request [:detail :default]
  [view model {:keys [uri], {:keys [saved], :as params} :params, :as request}]
  (let [item  (or (fetch-one model (dissoc params :model :saved))
                  (throw (ex-info "Not found." {:toucan.admin/response? true
                                                :status                 404
                                                :body                   "Not found."
                                                :headers                {"Content-Type" "text/plain"}})))
        title (detail-page-title view model item)]
    {:status  200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (page/render-page :detail
                {:title                      title
                 :contents-template-filename "toucan/admin/templates/detail-view.html.mustache"
                 :contents-data              {:title           title
                                              :saved           (boolean saved)
                                              :body            (table/table (table-style view model request)
                                                                 [item])
                                              :save-action-url uri}
                 :actions                    (actions view model item)
                 :crumbs                     (crumbs view model item)})}))
