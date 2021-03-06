;; This source code is dual-licensed under the Apache License, version
;; 2.0, and the Eclipse Public License, version 1.0.
;;
;; The APL v2.0:
;;
;; ----------------------------------------------------------------------------------
;; Copyright (c) 2014-2014 Michael S. Klishin, Alex Petrov, and the ClojureWerkz Team
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;; ----------------------------------------------------------------------------------
;;
;; The EPL v1.0:
;;
;; ----------------------------------------------------------------------------------
;; Copyright (c) 2014-2014 Michael S. Klishin, Alex Petrov, and the ClojureWerkz Team.
;; All rights reserved.
;;
;; This program and the accompanying materials are made available under the terms of
;; the Eclipse Public License Version 1.0,
;; which accompanies this distribution and is available at
;; http://www.eclipse.org/legal/epl-v10.html.
;; ----------------------------------------------------------------------------------

(ns clojurewerkz.eep.windows
  "Generic implementation of windowed operations"
  (:require [clojurewerkz.eep.clocks :as clocks]
            [com.ifesdjeen.utils.circular-buffer :as cb])
  (:import [java.util Timer TimerTask Date]))

;;
;; Implementation
;;

(defn sliding-window-simple
  "A simple sliding window. Sliding windows (here) have a fixed a-priori known size.

  Example: Sliding window of size 2 computing sum of values.

    t0     t1      (emit)   t2             (emit)       tN
  +---+  +---+---+          -...-...-
  | 1 |  | 2 | 1 |   <3>    : x : x :
  +---+  +---+---+          _...+---+---+               ...
             | 2 |              | 2 | 3 |    <5>
             +---+              +---+---+
                                    | 4 |
                                    +---+

   Useful to hold last `size` elements.
"
  [size aggregate emit-fn]
  (let [buffer (atom (cb/circular-buffer size))]
    (fn [value]
      (swap! buffer conj value)
      (when (cb/full? @buffer)
        (emit-fn (aggregate @buffer))))))

(defn tumbling-window-simple
  "A tumbling window. Tumbling windows (here) have a fixed a-priori known size.

   Example: Tumbling window of size 2 computing sum of values.

    t0     t1      (emit)    t2            t3         (emit)    t4
  +---+  +---+---+         -...-...-
  | 1 |  | 2 | 1 |   <3>   : x : x :
  +---+  +---+---+         -...+---+---+   +---+---+            ...
                                   | 3 |   | 4 | 3 |    <7>
                                   +---+   +---+---+

   Useful to accumulate `size` elements and aggreagate on overflow.
"
  [size aggregate emit-fn]
  (let [buffer (atom (cb/circular-buffer size))]
    (fn [value]
      (swap! buffer conj value)
      (when (cb/full? @buffer)
        (emit-fn (aggregate @buffer))
        (reset! buffer (cb/circular-buffer size))))))

(defn monotonic-window-simple
  "A simple monotonic window, that makes a clock tick on every call. Whenever
   clock is elapsed, runs `emit-fn`.

   In essence, it's an alternative implementation of tumbling-window that allows
   to use custom emission control rather than having a buffer overflow check.

   Useful for cases when emission should be controlled by arbitrary function,
   possibly unrelated to window contents."
  [clock-orig aggregate emit-fn]
  (let [clock  (atom clock-orig)
        buffer (atom [])]
    (fn [value]
      (swap! clock clocks/tick)
      (when (clocks/elapsed? @clock)
        (emit-fn (aggregate @buffer))
        (reset! buffer [])
        (swap! clock clocks/reset))
      (swap! buffer conj value))))

(defn timed-window-simple
  "A simple timed window, that runs on wall clock. Receives events and stores them
   until clock is elapsed, runs `emit-fn` for aggregation after that.

   In essence, it's an alternative implementation of tumbling-window or monotonic-window
   that allows wall clock control.

   Useful for accumulating events for time-bound events processing, accumulates events
   for a certain period of time (for example, 1 minute), and aggregates them."
  ([clock-orig tick-period aggregate emit-fn]
     (timed-window-simple clock-orig tick-period aggregate emit-fn  (Timer. true)))
  ([clock-orig tick-period aggregate emit-fn timer]
      (let [clock  (atom clock-orig)
            buffer (atom [])
            task   (proxy [TimerTask] []
                     (run []
                       (swap! clock clocks/tick)
                       (when (clocks/elapsed? @clock)
                         (emit-fn (aggregate @buffer))
                         (reset! buffer [])
                         (swap! clock clocks/reset))))]
        (.scheduleAtFixedRate timer task 0 tick-period)
        (fn [value]
          (swap! buffer conj value)))))
