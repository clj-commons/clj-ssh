(defbrick clj-ssh
  :images {:vmfest [{:image {:os-family :ubuntu :os-version-matches "12.04"
                             :os-64-bit true}
                     :selectors [:default]}]})
