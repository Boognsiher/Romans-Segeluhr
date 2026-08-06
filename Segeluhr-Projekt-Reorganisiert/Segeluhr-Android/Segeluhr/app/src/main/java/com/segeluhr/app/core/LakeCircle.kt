package com.segeluhr.app.core

/**
 * Ein einzelner Sicherheits-Kreis der See-Geofence (Abschnitt 6.5 sowie
 * docs/Erweiterung_Automatische_See_Erkennung.md). Für längliche/unregel-
 * mässige Seen reicht ein einzelner Kreis nicht aus, um den ganzen
 * befahrbaren Bereich abzudecken, ohne an den Enden ständig eine falsche
 * "Rand"-Warnung auszulösen — deshalb eine KETTE von Kreisen entlang der
 * Mittellinie statt eines einzelnen Mittelpunkts+Radius (Roman-Entscheidung
 * 06.08.2026). [LakeGeofenceEngine] gilt der Ort als sicher, sobald er
 * innerhalb IRGENDEINES dieser Kreise liegt.
 */
data class LakeCircle(val center: GeoPoint, val radiusM: Double)
