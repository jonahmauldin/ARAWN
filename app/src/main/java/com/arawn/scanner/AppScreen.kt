package com.arawn.scanner

/** Top-level navigation destinations shown in the bottom bar. */
enum class AppScreen(val label: String, val icon: String) {
    OPS     ("OPS",      "⌂"),
    RECON   ("RECON",    "∿"),
    MISSIONS("MISSIONS", "◎"),
    REPORTS ("REPORTS",  "▤"),
    VAULT   ("VAULT",    "⊗"),
    DOCS    ("DOCS",     "☰"),
}
