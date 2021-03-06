;; Contains methods for the string accumulator, the body part accumulator,
;; and the body part token.
;; This way the underlying data structures can be changed easily if needed,
;; and the complexity requirements can be assessed.
(ns scribble.types)


;; # String accumulator methods
;;
;; String accumulator is used when the reader reads and collects characters
;; from the stream.

(defn make-str-accum
  "Creates an empty string accumulator."
  ([]
    [])
  ([c]
    [c]))

(defn str-accum-pop
  "Removes the last `n` characters from `str-accum`."
  [str-accum n]
  (if (zero? n)
    str-accum
    (subvec str-accum 0 (- (count str-accum) n))))

(defn str-accum-push
  "Adds a character `c` to the end of `str-accum`."
  [str-accum c]
  (conj str-accum c))

(defn str-accum-finalize
  "Returns a string representing the contents of the accumulator."
  [str-accum]
  (clojure.string/join str-accum))


;; # Body part token methods
;;
;; When the body part is read, it is organized into tokens
;; containing strings or arbitrary forms, and some metadata,
;; which is readily available at read time,
;; but would require `O(n)` time to obtain during the postprocessing.

(deftype BodyToken [
  contents
  ; `true` if `contents` is a string "\n"
  ^boolean newline?
  ; `true` if `contents` is a string of whitespace characters,
  ; representing the leading whitespace in the body part.
  ^boolean leading-ws?
  ; `true` if `contents` is a string of whitespace characters,
  ; representing the trailing whitespace in the body part.
  ^boolean trailing-ws?])

(defn make-body-token
  "Creates a `BodyToken` with an optional metadata."
  [contents & {:keys [newline leading-ws trailing-ws]
               :or {newline false
                    leading-ws false
                    trailing-ws false}}]
  (BodyToken. contents
              (boolean newline)
              (boolean leading-ws)
              (boolean trailing-ws)))


;; # Body part accumulator methods
;;
;; Body part accumulator is used to collect `BodyToken`s
;; while reading the body part.

(defn make-body-accum
  "Creates an empty body part accumulator."
  []
  [])

(defn- body-accum-push
  "Adds a token to the end of the accumulator."
  [body-accum token]
  (conj body-accum token))

(defn body-accum-finalize
  "Converts the accumulator to a data structure
  more suitable for postprocessing."
  [body-accum]
  body-accum)


;; # Body accumulator helpers

(defn- push-trailing-ws
  "Wraps a string of trailing whitespace in a token
  and adds it to the accumulator."
  [body-accum s]
  (if (empty? s)
    body-accum
    (body-accum-push body-accum (make-body-token s :trailing-ws true))))

(defn- push-string
  "If the given string is non-empty, wraps it in a token
  and adds it to the accumulator."
  [body-accum s]
  (if (empty? s)
    body-accum
    (body-accum-push body-accum (make-body-token s))))

(defn- push-form
  "Wraps an arbitrary form in a token and adds it to the accumulator."
  [body-accum f]
  (body-accum-push body-accum (make-body-token f)))

(defn push-newline
  "Wraps a newline in a token and adds it to the accumulator."
  [body-accum]
  (body-accum-push body-accum (make-body-token "\n" :newline true)))


;; # Body- and string-accumulator combined updaters

(defn dump-leading-ws
  "Finalizes a string accumulator containing leading whitespace
  and pushes it to the body accumulator."
  [body-accum str-accum]
  (body-accum-push
    body-accum
    (make-body-token (str-accum-finalize str-accum) :leading-ws true)))

(defn- dump-string-verbatim
  "Finalizes a string accumulator containing an arbitrary string
  and pushes it to the body accumulator."
  [body-accum str-accum]
  (push-string body-accum (str-accum-finalize str-accum)))

(defn- split-trimr
  "Splits the string into two strings containing the trailing whitespace
  and the remaining part.
  Returns a vector `[main-part trailing-ws]`."
  [s]
  (let [trimmed-s (clojure.string/trimr s)
        count-full (count s)
        count-trimmed (count trimmed-s)]
    (if (= count-full count-trimmed)
      [s ""]
      [trimmed-s (subs s count-trimmed)])))

(defn dump-string
  "Finalizes a string accumulator containing an arbitrary string
  and pushes it to the body accumulator.
  If `str-accum` is empty, `body-accum` is returned unchanged.
  Otherwise, the string constructed from `str-accum`
  is split into the main part and the trailing whitespace part
  before the attachment to `body-accum`."
  [body-accum str-accum]
  (let [[main-part trailing-ws] (split-trimr (str-accum-finalize str-accum))]
    (-> body-accum
      (push-string main-part)
      (push-trailing-ws trailing-ws))))

(defn mark-for-splice
  "Marks the list to be spliced in the body part
  in the parent call to `read-body`."
  [l]
  (with-meta l {::splice true}))

(defn dump-nested-form
  "Pushes an arbitrary form to the accumulator,
  possibly finalizing and pushing the string accumulator first.
  Splices the list into the body accumulator, if it is marked with
  the corresponding meta tag."
  [body-accum str-accum nested-form leading-ws]
  (cond
    leading-ws
      (dump-nested-form
        (dump-leading-ws body-accum str-accum)
        (make-str-accum)
        nested-form
        false)

    ; it was a string: special case, append it to the accumulator
    (string? nested-form)
      ; FIXME: prepending is O(n)
      [body-accum (vec (concat str-accum nested-form))]

    ; an actual form
    :else
      (let [body-accum-with-str (dump-string-verbatim body-accum str-accum)
            body-accum
              (if (::splice (meta nested-form))
                (reduce push-form body-accum-with-str nested-form)
                (push-form body-accum-with-str nested-form))]
        [body-accum (make-str-accum)])))
