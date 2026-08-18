# Consumer ProGuard/R8 rules shipped to apps that depend on :overtake-maps.
# osmdroid and MapLibre ship their own consumer rules inside their AARs. This module merges no
# manifest components and instantiates nothing by name, so no keeps are required yet; Stages 1-4 add
# rules here only if the moved code introduces reflection.
