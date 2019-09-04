(ns toucan.admin.table
  "Tables table cells are rendered by a series of multimethods that dispatch off of a 'table style' (canonically
  prefixed by `:table/`), and/or a 'cell style' -- table styles control the appearance of a table as a whole, such as
  column order, while cell styles control the appearance of individual cells in a table.

  ### Table Styles

  In most cases each view defines its own table style, with related views deriving from one another when appropriate.
  You don't need to do anything special to define a table style, just mention it when you define a view:

    ;; The User List view (`:view/user-list`) uses the `:table/user` table style
    (views/defview :view/user-list \"Users\" :table/user)

  In the example above, the User List view defines a `:table/user` style; the related User Detail view defines
  `:table/user-editable`, which derives from `:table/user`.

  See also: `define-column-order`, to specify which order columns should appear in a rendered table.

  ### Cell Styles

  A table style defines which cell styles to use for various columns in the results; for example, the `:table/user`
  table style renders `:id` values using the `:user-id` cell style:

    (table/use-cell-style :table/user
      :id           :user-id
      :stripe-token :stripe-token
      :email        :email)

  New styles themselves can be defined with the `define-cell-style` macro; see its docstring for more details."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [toucan.admin.common :as common])
  (:import java.text.SimpleDateFormat
           java.util.Date))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Table Styles                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn table-style? [table-style]
  (common/isa? table-style :table-style))

(defmacro define-table-style
  ([table-style]
   (common/derive table-style :table-style))

  ([table-style parent-style]
   {:pre [(table-style? parent-style)]}
   (common/derive table-style parent-style)))

(defmulti column-order
  "Define the order columns should appear in when rendering tables with `table-style`. Impl for `define-column-order`
  macro; prefer using that macro to implementing this method directly."
  {:arglists '([table-style columns]), :style/indent 1}
  (fn [table-style _]
    (keyword table-style))
  :hierarchy #'common/hierarchy)

(defmethod column-order :default
  [_ ks]
  (let [keyset (set ks)]
    (if (contains? keyset :id)
      (cons :id (sort (disj keyset :id)))
      (sort keyset))))

(defmacro define-column-order
  "Specify the order columns should appear when rendering tables with `table-style`.

    (table/define-column-order :table/user
      [:id :email :company :first-name :last-name :stripe-token])"
  {:style/indent 1}
  [table-style columns]
  `(defmethod column-order ~table-style
     [~'_ ~'_]
     ~columns))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Cell Styles                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn cell-style? [cell-style]
  (common/isa? cell-style :cell-style))

(defmulti cell-style-template
  "Mustache template filename for rendering table cells with `cell-style`. Part of the impl of the `define-cell-style`
  macro; prefer using that macro to implementing `cell-style-template` directly."
  {:arglists '([cell-style])}
  keyword
  :hierarchy #'common/hierarchy)

(defmethod cell-style-template :default
  [cell-style]
  "toucan/admin/templates/table/cell/default.html.mustache")

(defmulti cell-style-transform
  "Transform a single `value` before rendering the associated Mustache template for `cell-style`. Part of the impl of
  the `define-cell-style` macro; prefer using that macro to implementing this directly."
  {:arglists '([cell-style value])}
  (fn [cell-style _] (keyword cell-style))
  :hierarchy #'common/hierarchy)

(defmethod cell-style-transform :default
  [_ value]
  value)

(defmacro define-cell-style
  "Define a new `cell-style`, using mustache template named by `template-filename` to render values of the cell style.
  Table cell templates are normal Mustache templates that should take a single parameter, `:value`.

  This macro optionally accepts a value binding and body; if supplied, the can be used to transform values before the
  are passed to the template.

    ;; define the `:token-state-editable` cell style, which uses the `token-state-editable.html.mustache` template;
    ;;  transform the values into a map with four keys before rendering the cell template
    (table/define-cell-style :token-state-editable
      \"toucan/admin/templates/table/cell/token-state-editable.html.mustache\")

    ;; Same as example above, but transform values before rendering them with the template
    (table/define-cell-style :token-state-editable
      \"toucan/admin/templates/table/cell/token-state-editable.html.mustache\"
      [token-state]
      (assoc (zipmap [:trial :valid :expired :expired-trial] (repeat false))
        (keyword token-state) true))"
  {:style/indent :defn}
  ([cell-style template-filename]
   `(define-cell-style ~cell-style ~template-filename [~'_]))

  ([cell-style template-filename [value-binding] & body]
   (let [cell-style-symb (gensym "cell-style-")]
     `(let [~cell-style-symb   (keyword ~cell-style)
            template-filename# ~template-filename]
        (common/derive ~cell-style-symb :cell-style)
        (defmethod cell-style-template ~cell-style-symb
          [~'_]
          template-filename#)
        ~(when (seq body)
           `(defmethod cell-style-transform ~cell-style-symb
              [~'&cell-style ~value-binding]
              ~@body))))))

(defmulti cell-style*
  "Specify which cell style should be used for when rendering a table with `table-style` for values of `k`. This is part
  of the internal implementation of the `use-cell-style` macro; prefer using that to using this directly."
  {:arglists '([table-style k])}
  (fn [table-style k]
    [(keyword table-style) (keyword k)])
  :hierarchy #'common/hierarchy)

(defmethod cell-style* :default
  [_ _]
  nil)

(defn cell-style [table-style k]
  (let [table-style (keyword table-style)
        k           (keyword table-style)])
  (or (cell-style* table-style k)
      (cell-style* :default k)))

(defmacro use-cell-style
  "Specify which cell styles should be used for various columns when rendering a table with `table-style`. Args should
  be pairs of keyword column name -> cell style.

    ;; `:table/user` table style should render values of `:id` with `:user-id` cell style, values of `:stripe-token`
    ;; with the `:stripe-token` cell style, etc.
    (table/use-cell-style :table/user
      :id           :user-id
      :stripe-token :stripe-token
      :email        :email)"
  {:style/indent 1}
  [table-style column-name style & more]
  `(do
     (defmethod cell-style* [~table-style ~column-name] [~'_ ~'_] ~style)
     ~(when (seq more)
        `(use-cell-style ~table-style ~@more))))

(defmacro use-global-cell-style
  {:style/indent 0}
  [column-name style & more]
  `(use-cell-style :default ~column-name ~style ~@more))

(use-cell-style :default
  :id         :id
  :email      :email
  :created    :datetime
  :created-at :datetime
  :created_at :datetime
  :updated    :datetime
  :updated-at :datetime
  :updated_at :datetime)


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      View                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:dynamic *page-size*
  "Number of entires to show per page in list table views."
  20)

(defn render-cell
  "Render a single cell for in a table with `table-style` as HTML. Finds corresponding cell style and Mustache template
  filename for column `k`, and transforms value `v` as approrpiate before rendering the template."
  [table-style k v]
  (let [cell-style (cell-style table-style k)
        template   (cell-style-template cell-style)
        v          (cell-style-transform cell-style v)]
    ;; uncomment this when you need to debug cell rendering!
    #_(println (list 'render-template template {:value v}))
    (common/render-template template {:value v})))

(defn- columns
  [table-style row]
  (or (column-order table-style (keys row))
      (keys row)))

(defn render-row [table-style row]
  (let [ks (columns table-style row)]
    (for [k ks]
      (render-cell table-style k (get row k)))))

(defn render-rows [table-style rows]
  (for [row rows]
    {:vals (render-row table-style row)}))

(defn table
  {:style/indent 1}
  [table-style [first-row :as rows] & {:as options}]
  (try
    (common/render-template
     "toucan/admin/templates/table.html.mustache"
     (merge
      (let [columns (columns table-style first-row)]
        {:columns (map (comp str/capitalize #(str/replace % #"[-_]" " ") name) columns)
         :rows    (render-rows table-style rows)})
      options))
    (catch Throwable e
      (log/errorf e "Failed to render table"))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               Predefined-styles                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(define-cell-style :boolean "toucan/admin/templates/table/cell/boolean.html.mustache"
  [value]
  (boolean value))

(let [formatter (SimpleDateFormat. "MMM d, yyyy h:mm a (zzzz)")]
  (defn- format-datetime [^Date date]
    (when date
      (.format formatter date))))

(define-cell-style :datetime "toucan/admin/templates/table/cell/datetime.html.mustache"
  [datetime]
  (format-datetime datetime))

(let [formatter (SimpleDateFormat. "MMM d, yyyy")]
  (defn- format-date [^Date date]
    (when date
      (.format formatter date))))

(define-cell-style :date "toucan/admin/templates/table/cell/datetime.html.mustache"
  [date]
  (format-date date))

(define-cell-style :email "toucan/admin/templates/table/cell/email.html.mustache")

(define-cell-style :id "toucan/admin/templates/table/cell/id.html.mustache"
  [id]
  {:model (name common/*model*)
   :id    id})
