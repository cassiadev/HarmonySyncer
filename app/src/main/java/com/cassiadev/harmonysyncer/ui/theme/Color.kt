package com.cassiadev.harmonysyncer.ui.theme

import androidx.compose.ui.graphics.Color

// Light is the reference: values are the design/css/tokens.css OKLCH tokens converted to sRGB.
// --bg is a subtle fill (timing row, icon tile); the page itself is --surface, as in design/css/app.css.
val LightFill = Color(0xFFF8F8F8)
val LightSurface = Color(0xFFFFFFFF)
val LightForeground = Color(0xFF182028)
val LightMuted = Color(0xFF5C6C7C)
val LightBorder = Color(0xFFE0E8F0)
val LightAccent = Color(0xFF006DEC)
val LightVoice = Color(0xFF007165)
val LightDanger = Color(0xFFAC2724)
val LightSuccess = Color(0xFF006B39)
val LightOnAccent = Color(0xFFFFFFFF)

// As text on the Audio A card tint the design accent reaches only 4.34:1, so text there uses this
// one. Everywhere else (white page 4.77:1, buttons, sliders, icons) keeps LightAccent.
val LightAccentOnTint = Color(0xFF0267DC)

// Dark keeps each token's hue and only moves its lightness; it is used only in system dark mode.
val DarkFill = Color(0xFF0B1015)
val DarkSurface = Color(0xFF161B21)
val DarkForeground = Color(0xFFE7ECF0)
val DarkMuted = Color(0xFF99A6B4)
val DarkBorder = Color(0xFF2D343A)
val DarkAccent = Color(0xFF69A6FB)
val DarkVoice = Color(0xFF56BFB1)
val DarkDanger = Color(0xFFEF7F74)
val DarkSuccess = Color(0xFF66C189)
val DarkOnAccent = Color(0xFF0B1015)
